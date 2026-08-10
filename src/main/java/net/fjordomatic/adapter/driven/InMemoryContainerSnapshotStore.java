package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.DockerService.PortMapping;
import net.fjordomatic.domain.DockerService.ServiceEndpoint;
import net.fjordomatic.domain.LanAnchor;
import net.fjordomatic.domain.PublishableService;
import net.fjordomatic.domain.PublishableService.PublishableSource;
import net.fjordomatic.domain.ReverseProxyRoute;
import net.fjordomatic.domain.ScopedImage;
import net.fjordomatic.domain.UpdateAvailability;
import net.fjordomatic.domain.FjordServerCatalogue;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers;
import net.fjordomatic.domain.port.ForResolvingFjordServerIdentity;
import net.fjordomatic.domain.port.ForDiscoveringFjordServerContainers;
import net.fjordomatic.domain.port.ForGettingFjordServerDockerServices;
import net.fjordomatic.domain.port.ForStoringContainerSnapshots;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory store for the cached container scrapes. Owns the peer- and Fjord-server snapshots and the
 * last image-update sweep's verdicts that used to be {@code volatile} fields on {@code ContainerService} —
 * a {@code *Service} must not implement a driven ({@code For*}) port, so the state and the read-side
 * driven ports moved here. The scrape/sweep use cases in {@code ContainerService} write through
 * {@link ForStoringContainerSnapshots}; consumers read the decorated views through the discovery ports.
 *
 * <p>Mirrors {@link InMemoryLanReachabilityCache}: {@code volatile} fields written from the scrape
 * scheduler and read from request threads.
 */
@Component
public class InMemoryContainerSnapshotStore implements
    ForDiscoveringFjordServerContainers,
    ForDiscoveringPeerContainers,
    ForGettingFjordServerDockerServices,
    ForStoringContainerSnapshots {

    private volatile List<PeerContainers> peerContainersSnapshot = List.of();
    private volatile List<DockerService> fjordServerContainersSnapshot = List.of();
    private volatile Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts = Map.of();

    private final String fjordNetworkName;
    private final String dockerGatewayIp;
    private final ForResolvingFjordServerIdentity fjordServerIdentity;

    @Autowired
    public InMemoryContainerSnapshotStore(ForResolvingFjordServerIdentity fjordServerIdentity) {
        this(System.getenv().getOrDefault("VAIER_NETWORK_NAME", "vaier-network"),
            System.getenv().getOrDefault("VAIER_DOCKER_GATEWAY", "172.20.0.1"), fjordServerIdentity);
    }

    public InMemoryContainerSnapshotStore(String fjordNetworkName, String dockerGatewayIp,
                                          ForResolvingFjordServerIdentity fjordServerIdentity) {
        this.fjordNetworkName = fjordNetworkName;
        this.dockerGatewayIp = dockerGatewayIp;
        this.fjordServerIdentity = fjordServerIdentity;
    }

    // --- read side (driven query ports) ---

    /** Cached Fjord-server scrape, carrying the last sweep's update-available verdicts. */
    @Override
    public List<DockerService> discover() {
        return withUpdateVerdicts(fjordServerIdentity.identity().value(), fjordServerContainersSnapshot);
    }

    /** Cached server-peer scrape, each container carrying the last sweep's verdicts. */
    @Override
    public List<PeerContainers> discoverAll() {
        return peerContainersSnapshot.stream()
            .map(peer -> new PeerContainers(peer.machineId(), peer.peerId(), peer.vpnIp(), peer.status(),
                withUpdateVerdicts(peer.machineId(), peer.containers()),
                peer.wireguardOutdated(), peer.wireguardExpectedImage()))
            .toList();
    }

    @Override
    public List<PublishableService> getUnpublishedFjordServerServices(List<ReverseProxyRoute> existingRoutes) {
        List<PublishableService> result = new ArrayList<>();
        fjordServerContainersSnapshot.forEach(container -> {
            String name = container.containerName();
            if (FjordServerCatalogue.isExcluded(name)) return;

            container.ports().stream()
                .filter(p -> "tcp".equals(p.type()))
                .filter(p -> FjordServerCatalogue.isPublishablePort(name, p.privatePort()))
                .forEach(p -> firstUnroutedEndpoint(container, p, existingRoutes)
                    .ifPresent(ep -> result.add(new PublishableService(
                        PublishableSource.FJORD_SERVER,
                        // The Fjord server is the one machine that cannot be found by searching a store,
                        // so its identity comes from the port that owns read-and-assign-once.
                        fjordServerIdentity.identity().value(),
                        null,
                        ep.address(),
                        container.containerName(),
                        ep.port(),
                        FjordServerCatalogue.rootRedirectPath(name),
                        false
                    ))));
        });
        return result;
    }

    /**
     * The endpoint a publish of {@code port} would be written with, or empty when the port is unreachable
     * or already routed. Both decisions are the domain's: {@code everyReachableEndpoint} knows the
     * spellings of one service and leads with the preferred one, {@code hasRouteForAny} knows that a route
     * under any of them means published.
     */
    private Optional<ServiceEndpoint> firstUnroutedEndpoint(
            DockerService container, PortMapping port, List<ReverseProxyRoute> existingRoutes) {
        List<ServiceEndpoint> endpoints =
            container.everyReachableEndpoint(port, fjordNetworkName, dockerGatewayIp);
        return ReverseProxyRoute.hasRouteForAny(existingRoutes, endpoints)
            ? Optional.empty()
            : endpoints.stream().findFirst();
    }

    /**
     * Decorate the containers of {@code machine} with the last sweep's verdicts. Keyed by
     * {@link ScopedImage} so the mark matches the verdict the sweep settled for THAT machine's copy of
     * the image. An unswept image reads {@link UpdateAvailability#UNKNOWN} — that rule is
     * {@code DockerService}'s, stated once, so the two can never disagree.
     */
    private List<DockerService> withUpdateVerdicts(String machineId, List<DockerService> containers) {
        Map<ScopedImage, UpdateAvailability> verdicts = imageUpdateVerdicts;
        if (verdicts.isEmpty()) {
            return containers;
        }
        return containers.stream()
            .map(c -> c.withUpdateAvailability(verdicts.get(new ScopedImage(machineId, c.image()))))
            .toList();
    }

    // --- write / owner side ---

    @Override
    public void storePeerContainers(List<PeerContainers> peers) {
        this.peerContainersSnapshot = peers;
    }

    @Override
    public void storeFjordServerContainers(List<DockerService> containers) {
        this.fjordServerContainersSnapshot = containers;
    }

    @Override
    public synchronized void storeImageUpdateVerdicts(Map<ScopedImage, UpdateAvailability> verdicts) {
        this.imageUpdateVerdicts = verdicts;
    }

    /**
     * Drop one image's remembered verdict, leaving every other one exactly as it was — a copy-on-write
     * replacement, since the map a sweep hands in may be immutable.
     *
     * <p>Synchronized with {@link #storeImageUpdateVerdicts} so the read-copy-write cannot interleave with
     * a sweep storing its results: without it, a sweep finishing mid-forget would be silently reverted to
     * the map this read a moment earlier.
     */
    @Override
    public synchronized void forgetImageUpdateVerdict(ScopedImage image) {
        if (!imageUpdateVerdicts.containsKey(image)) {
            return;
        }
        Map<ScopedImage, UpdateAvailability> remaining = new HashMap<>(imageUpdateVerdicts);
        remaining.remove(image);
        this.imageUpdateVerdicts = Map.copyOf(remaining);
    }

    @Override
    public List<PeerContainers> peerContainers() {
        return peerContainersSnapshot;
    }

    @Override
    public List<DockerService> fjordServerContainers() {
        return fjordServerContainersSnapshot;
    }

    @Override
    public Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts() {
        return imageUpdateVerdicts;
    }
}
