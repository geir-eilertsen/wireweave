package net.fjordomatic.application.service;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.DiscoverLanServerContainersUseCase;
import net.fjordomatic.application.DiscoverPeerContainersUseCase;
import net.fjordomatic.application.DiscoverFjordServerContainersUseCase;
import net.fjordomatic.application.GetFjordServerDockerServicesUseCase;
import net.fjordomatic.application.GetServerInfoUseCase;
import net.fjordomatic.domain.PublishableService;
import net.fjordomatic.application.CheckForImageUpdatesUseCase;
import net.fjordomatic.application.RefreshContainerStateUseCase;
import net.fjordomatic.application.SweepImageUpdatesUseCase;
import net.fjordomatic.application.UpdateContainerImageUseCase;
import net.fjordomatic.domain.ContainerUpdate;
import net.fjordomatic.domain.ContainerUpdate.Settlement;
import net.fjordomatic.domain.DockerCommandAccess;
import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.ImageUpdateSweep;
import net.fjordomatic.domain.ImageUpdateSweep.MachineContainers;
import net.fjordomatic.domain.ImageUpdateTracker;
import net.fjordomatic.domain.LanAnchor;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.ScopedImage;
import net.fjordomatic.domain.SshTarget;
import net.fjordomatic.domain.UpdateAvailability;
import net.fjordomatic.domain.UpdateCheckFloor;
import net.fjordomatic.domain.UpdateCheckOutcome;
import net.fjordomatic.domain.ContainerUpdateEligibility;
import net.fjordomatic.domain.FjordServerCatalogue;
import net.fjordomatic.domain.ReverseProxyRoute;
import net.fjordomatic.domain.Server;
import net.fjordomatic.domain.VpnClient;
import net.fjordomatic.domain.WireguardClientImage;
import net.fjordomatic.domain.port.ForDiscoveringLanServerContainers;
import net.fjordomatic.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.fjordomatic.domain.port.ForCheckingDockerCommandAccess;
import net.fjordomatic.domain.port.ForDiscoveringFjordServerContainers;
import net.fjordomatic.domain.port.ForGettingLanServerScrape;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForGettingServerInfo;
import net.fjordomatic.domain.port.ForGettingFjordServerDockerServices;
import net.fjordomatic.domain.port.ForGettingVpnClients;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.domain.port.ForResolvingPeerIds;
import net.fjordomatic.domain.port.ForResolvingRegistryDigest;
import net.fjordomatic.domain.port.ForResolvingSshTargets;
import net.fjordomatic.domain.port.ForResolvingFjordServerIdentity;
import net.fjordomatic.domain.port.ForRunningSshCommands;
import net.fjordomatic.domain.port.ForStoringContainerSnapshots;
import net.fjordomatic.domain.port.ForTrackingHostKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class ContainerService implements
    DiscoverFjordServerContainersUseCase,
    DiscoverPeerContainersUseCase,
    DiscoverLanServerContainersUseCase,
    GetServerInfoUseCase,
    GetFjordServerDockerServicesUseCase,
    RefreshContainerStateUseCase,
    SweepImageUpdatesUseCase,
    UpdateContainerImageUseCase,
    CheckForImageUpdatesUseCase {

    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerIds forResolvingPeerIds;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingRegistryDigest forResolvingRegistryDigest;
    private final ForPublishingEvents forPublishingEvents;
    private final ImageUpdateTracker imageUpdateTracker;
    private final UpdateCheckFloor updateCheckFloor;
    private final Clock clock;
    // The cached scrapes + sweep verdicts now live in InMemoryContainerSnapshotStore (a service must
    // not implement the driven discovery ports); the scrape/sweep use cases here write and read raw
    // through the store, and consumers read the decorated views through the discovery ports.
    private final ForStoringContainerSnapshots snapshotStore;
    private final ForDiscoveringFjordServerContainers fjordServerContainers;
    private final ForDiscoveringPeerContainers peerContainers;
    private final ForGettingFjordServerDockerServices fjordServerDockerServices;
    private final ForDiscoveringLanServerContainers lanServerContainers;
    // The Fjord server's own identity — the one machine that appears in no store, so its containers have
    // no other way to be scoped to a host.
    private final ForResolvingFjordServerIdentity fjordServerIdentity;
    // The debounced LAN-server scrape, read rather than re-scraped: an update must be judged against
    // what Fjord already knows, not by going and asking every LAN server while a request thread waits.
    private final ForGettingLanServerScrape lanServerScrape;
    // The SSH path an update travels. Deliberately SSH and not the Docker API: recreating a container
    // through the daemon would mean widening docker-socket-proxy to create and remove.
    private final ForResolvingSshTargets sshTargets;
    private final ForRunningSshCommands sshCommands;
    private final ForTrackingHostKeys hostKeys;
    /** Where an accepted update is carried out, off whatever thread asked for it. */
    private final Executor updateExecutor;
    // What the disk sweep last saw of each machine's Docker access — read at scrape time, so a container
    // on a machine Fjord cannot drive Docker on is never offered an update that would die on it.
    private final ForCheckingDockerCommandAccess dockerAccess;

    /**
     * Where a settled update-available verdict is pushed. The container payloads already ride this
     * topic/event — {@code DockerEventListener} publishes it when a container changes state, and the Explorer
     * re-reads its containers on it — so a re-checked verdict travels the road that already exists rather than
     * inventing a second one for the same payload.
     */
    private static final String SSE_TOPIC = "published-services";
    private static final String SSE_EVENT = "service-updated";
    private static final String SSE_DATA = "image-updates-checked";

    @Autowired
    public ContainerService(ForGettingServerInfo forGettingServerInfo,
                            ForGettingVpnClients forGettingVpnClients,
                            ForResolvingPeerIds forResolvingPeerIds,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForResolvingRegistryDigest forResolvingRegistryDigest,
                            ForPublishingEvents forPublishingEvents,
                            ImageUpdateTracker imageUpdateTracker,
                            Clock clock,
                            ForStoringContainerSnapshots snapshotStore,
                            ForDiscoveringFjordServerContainers fjordServerContainers,
                            ForDiscoveringPeerContainers peerContainers,
                            ForGettingFjordServerDockerServices fjordServerDockerServices,
                            ForDiscoveringLanServerContainers lanServerContainers,
                            ForResolvingFjordServerIdentity fjordServerIdentity,
                            ForGettingLanServerScrape lanServerScrape,
                            ForResolvingSshTargets sshTargets,
                            ForRunningSshCommands sshCommands,
                            ForTrackingHostKeys hostKeys,
                            ForCheckingDockerCommandAccess dockerAccess) {
        // A single thread: updates on one Fjord are serialised rather than piling several multi-minute
        // pulls onto a fleet's bandwidth at once, and no request thread ever waits on one.
        this(forGettingServerInfo, forGettingVpnClients, forResolvingPeerIds, forGettingPeerConfigurations,
            forResolvingRegistryDigest, forPublishingEvents, imageUpdateTracker, clock, snapshotStore,
            fjordServerContainers, peerContainers, fjordServerDockerServices, lanServerContainers,
            fjordServerIdentity, lanServerScrape, sshTargets, sshCommands, hostKeys, dockerAccess,
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "container-update");
                thread.setDaemon(true);
                return thread;
            }));
    }

    ContainerService(ForGettingServerInfo forGettingServerInfo,
                     ForGettingVpnClients forGettingVpnClients,
                     ForResolvingPeerIds forResolvingPeerIds,
                     ForGettingPeerConfigurations forGettingPeerConfigurations,
                     ForResolvingRegistryDigest forResolvingRegistryDigest,
                     ForPublishingEvents forPublishingEvents,
                     ImageUpdateTracker imageUpdateTracker,
                     Clock clock,
                     ForStoringContainerSnapshots snapshotStore,
                     ForDiscoveringFjordServerContainers fjordServerContainers,
                     ForDiscoveringPeerContainers peerContainers,
                     ForGettingFjordServerDockerServices fjordServerDockerServices,
                     ForDiscoveringLanServerContainers lanServerContainers,
                     ForResolvingFjordServerIdentity fjordServerIdentity,
                     ForGettingLanServerScrape lanServerScrape,
                     ForResolvingSshTargets sshTargets,
                     ForRunningSshCommands sshCommands,
                     ForTrackingHostKeys hostKeys,
                     ForCheckingDockerCommandAccess dockerAccess,
                     Executor updateExecutor) {
        this.forGettingServerInfo = forGettingServerInfo;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerIds = forResolvingPeerIds;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingRegistryDigest = forResolvingRegistryDigest;
        this.forPublishingEvents = forPublishingEvents;
        this.imageUpdateTracker = imageUpdateTracker;
        // Owned outright, unlike the tracker, because this service is the only thing that can spend the
        // registries' rate limit on demand — one singleton service, one floor, so the limit is a limit. If a
        // second entry point ever forces a sweep it must share THIS floor (a bean, as the tracker had to
        // become); two floors would each admit a check a minute and quietly double the ceiling.
        this.updateCheckFloor = new UpdateCheckFloor(clock);
        this.clock = clock;
        // The store adapter backs the write side and the three read ports; Spring resolves each of
        // these interfaces to that single bean (a service depends on ports, never the adapter class).
        this.snapshotStore = snapshotStore;
        this.fjordServerContainers = fjordServerContainers;
        this.peerContainers = peerContainers;
        this.fjordServerDockerServices = fjordServerDockerServices;
        this.lanServerContainers = lanServerContainers;
        this.fjordServerIdentity = fjordServerIdentity;
        this.lanServerScrape = lanServerScrape;
        this.sshTargets = sshTargets;
        this.sshCommands = sshCommands;
        this.hostKeys = hostKeys;
        this.dockerAccess = dockerAccess;
        this.updateExecutor = updateExecutor;
    }

    /** Cache read — backed by {@link #refresh()}; the launchpad never scrapes Docker on-thread. */
    @Override
    public List<DockerService> discover() {
        return fjordServerContainers.discover();
    }

    /**
     * Ask the registries what they serve now, for every container Fjord can see on its own host and on its
     * server peers — the sweep the daily watcher drives.
     *
     * <p>The service decides nothing here: it reads its snapshots, hands the domain the registry port, and
     * keeps the answer. {@link ImageUpdateSweep} is what judges which images are worth asking about, asks each
     * distinct one exactly once, and rules an unreachable registry unknown rather than outdated.
     *
     * <p>Reads the cached snapshots rather than re-scraping: the sweep asks registries, not hosts, and the
     * scheduler has already refreshed these within the last 30 seconds.
     *
     * <p>Pushes what it found, on the same domain decision the operator's own check uses: only a moved verdict
     * is worth repainting. Without it the alert mail arrives while every open Explorer still shows no mark.
     */
    @Override
    public Map<ScopedImage, UpdateAvailability> sweepImageUpdates() {
        Map<ScopedImage, UpdateAvailability> before = snapshotStore.imageUpdateVerdicts();
        Map<ScopedImage, UpdateAvailability> verdicts =
            ImageUpdateSweep.sweep(everyContainerFjordCanSee(), forResolvingRegistryDigest);
        snapshotStore.storeImageUpdateVerdicts(verdicts);
        if (UpdateCheckOutcome.checked(before, verdicts, clock.instant()).worthPublishing()) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, SSE_DATA);
        }
        return verdicts;
    }

    /**
     * The update check the operator asked for: re-scrape, ask the registries afresh, keep the answer, push it.
     *
     * <p>The service decides nothing here either — it sequences four domain calls and passes the ports in.
     * {@link UpdateCheckFloor} rules whether the registries may be asked at all, {@link ImageUpdateSweep}
     * judges the images, {@link ImageUpdateTracker} rules what the check may do to the alert state, and
     * {@link UpdateCheckOutcome} decides what the operator is told and whether anything is worth pushing.
     *
     * <p><b>The scrape comes first, and that ordering is the feature.</b> The local digest is whatever the 30s
     * container scrape last saw, and the operator clicks seconds after pulling — so sweeping the snapshot in
     * hand would read the pre-pull digest and confirm the very mark the button was pressed to clear. Refusing
     * remembered registry answers (the other half, in {@code sweepFresh}) fixes the mirror image of the same
     * bug. Both are needed: they are stale on opposite sides of the comparison.
     *
     * <p>Refused checks cost nothing at all — no scrape, no registry request — because the abuse a floor is
     * there to stop must not simply move from the registries onto the fleet's Docker daemons.
     */
    @Override
    public UpdateCheckOutcome checkForImageUpdates() {
        UpdateCheckFloor.Admission admission = updateCheckFloor.admit();
        if (!admission.admitted()) {
            return UpdateCheckOutcome.coalesced(admission.lastCheckedAt());
        }

        refresh();
        Map<ScopedImage, UpdateAvailability> before = snapshotStore.imageUpdateVerdicts();
        Map<ScopedImage, UpdateAvailability> after =
            ImageUpdateSweep.sweepFresh(everyContainerFjordCanSee(), forResolvingRegistryDigest);
        snapshotStore.storeImageUpdateVerdicts(after);
        imageUpdateTracker.clearUpToDate(after);

        UpdateCheckOutcome outcome = UpdateCheckOutcome.checked(before, after, admission.lastCheckedAt());
        if (outcome.worthPublishing()) {
            forPublishingEvents.publish(SSE_TOPIC, SSE_EVENT, SSE_DATA);
        }
        return outcome;
    }

    /**
     * The Fjord server's own containers and its server peers', each group carrying the IDENTITY of the
     * machine it came from so the sweep can scope every verdict to a host. A peer in no machine registry has
     * no identity, so its containers are not swept — there is nothing to scope a verdict to, and scoping it
     * to a name would fold two machines' verdicts together the moment they shared one. LAN-server containers
     * are not swept yet.
     */
    private List<MachineContainers> everyContainerFjordCanSee() {
        List<MachineContainers> machines = new ArrayList<>();
        // Fjord's own stack never reaches the sweep (#353) — the domain says which containers those are,
        // and they are dropped here, before any registry is asked, rather than swept and then hidden.
        machines.add(new MachineContainers(fjordServerIdentity.identity().value(),
            FjordServerCatalogue.sweepable(snapshotStore.fjordServerContainers())));
        snapshotStore.peerContainers().stream()
            .filter(peer -> peer.machineId() != null)
            .forEach(peer -> machines.add(new MachineContainers(peer.machineId(), peer.containers())));
        return machines;
    }

    /**
     * The Fjord server's own containers, each carrying the domain's verdict on whether Fjord may offer to
     * update its image. Judged here, at the one point that knows WHICH machine was scraped: the same
     * container name means Fjord's own stack on this host and the operator's container on any other, and
     * a browser handed an unjudged container would have to work that out for itself.
     */
    List<DockerService> scrapeFjordServerContainers() {
        return ContainerUpdateEligibility.judgeFjordServerContainers(
            forGettingServerInfo.getServicesWithExposedPorts(Server.fjordServer()),
            dockerAccess.accessFor(fjordServerIdentity.identity()));
    }

    @Override
    public List<DockerService> getServicesWithExposedPorts(Server server) {
        return forGettingServerInfo.getServicesWithExposedPorts(server);
    }

    /** Cache read — the launchpad and {@code /docker-services/peers} never scrape on-thread. */
    @Override
    public List<PeerContainers> discoverAll() {
        return peerContainers.discoverAll();
    }

    @Override
    public void refresh() {
        try {
            snapshotStore.storePeerContainers(scrapePeerContainers());
        } catch (Exception e) {
            log.warn("Peer container scrape failed, keeping previous snapshot: {}", e.getMessage());
        }
        try {
            snapshotStore.storeFjordServerContainers(scrapeFjordServerContainers());
        } catch (Exception e) {
            log.warn("Fjord-server container scrape failed, keeping previous snapshot: {}", e.getMessage());
        }
    }

    /**
     * Live scrape of every server-peer's Docker daemon over the VPN. Slow, and slow-to-fail
     * for an unreachable peer — only {@link #refresh()} (driven by the scheduler) calls it.
     */
    List<PeerContainers> scrapePeerContainers() {
        List<VpnClient> clients = forGettingVpnClients.getClients();
        List<PeerContainers> results = new ArrayList<>();

        for (VpnClient client : clients) {
            String vpnIp = client.vpnIp();
            String peerId = forResolvingPeerIds.resolvePeerIdByIp(vpnIp);

            var storedConfig = forGettingPeerConfigurations.getPeerConfigByIp(vpnIp);
            MachineType peerType = storedConfig
                    .map(ForGettingPeerConfigurations.PeerConfiguration::peerType)
                    .orElse(MachineType.UBUNTU_SERVER);
            // Read, never minted: a live WireGuard peer with no config on disk is in no machine registry,
            // so it reports no identity rather than a fabricated one that would join to nothing.
            String machineId = storedConfig
                    .map(ForGettingPeerConfigurations.PeerConfiguration::machineId)
                    .map(MachineId::value)
                    .orElse(null);

            if (!peerType.isVpnPeer() || !peerType.isServerType()) {
                log.debug("Skipping Docker discovery for non-server peer {} ({}) of type {}", peerId, vpnIp, peerType);
                continue;
            }

            if (!client.isConnected()) {
                log.debug("Skipping Docker discovery for disconnected peer {} ({})", peerId, vpnIp);
                results.add(new PeerContainers(machineId, peerId, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
                continue;
            }

            try {
                Server server = new Server(vpnIp, 2375, false);
                // A peer's containers are the operator's own, whatever they are named — nothing here is
                // Fjord's own stack, so a peer's traefik stays the operator's to update.
                List<DockerService> containers = ContainerUpdateEligibility.judgeOperatorContainers(
                    forGettingServerInfo.getServicesWithExposedPorts(server),
                    // A live peer with no stored config has no identity, so nothing is known about its
                    // Docker access — which reads UNKNOWN, and UNKNOWN keeps the button.
                    machineId == null ? DockerCommandAccess.UNKNOWN
                        : dockerAccess.accessFor(MachineId.of(machineId)));
                log.info("Discovered {} containers on peer {} ({})", containers.size(), peerId, vpnIp);
                results.add(new PeerContainers(machineId, peerId, vpnIp, "OK", containers, WireguardClientImage.anyOutdated(containers), WireguardClientImage.EXPECTED));
            } catch (Exception e) {
                log.warn("Failed to query Docker on peer {} ({}): {}", peerId, vpnIp, e.getMessage());
                results.add(new PeerContainers(machineId, peerId, vpnIp, "UNREACHABLE", List.of(), false, WireguardClientImage.EXPECTED));
            }
        }

        return results;
    }

    /** Live LAN-server scrape lives in LanServerContainerDiscoveryAdapter; delegate to the port. */
    @Override
    public List<LanServerContainers> discoverAllLanServerContainers() {
        return lanServerContainers.discoverAllLanServerContainers();
    }

    @Override
    public LanServerContainers discoverLanServerContainersForHost(String name) {
        return lanServerContainers.discoverLanServerContainersForHost(name);
    }

    /**
     * Update one container to the image its registry now serves (#352).
     *
     * <p>The service decides nothing: it gathers the containers Fjord has scraped from that machine, hands
     * them to {@link ContainerUpdate}, which rules whether the update may happen at all and what compose
     * will be asked to do, resolves the machine's SSH target, and hands the run off.
     *
     * <p><b>Everything that can refuse, refuses before the hand-off.</b> An unknown container, a container
     * Fjord will not recreate and a machine with no stored credential all throw here, on the caller's
     * thread, so the operator gets a 4xx that says which instead of a spinner and a silent event that never
     * arrives. Only what genuinely takes minutes — the pull and the recreate — crosses onto the executor.
     */
    @Override
    public void updateContainerImage(MachineId machineId, String containerName) {
        ContainerUpdate update =
            ContainerUpdate.of(machineId, containerName, containersOn(machineId));
        SshTarget target = sshTargets.resolve(machineId);
        updateExecutor.execute(() -> settle(update, target));
    }

    /**
     * Carry an accepted update out and announce how it ended — always, whatever happened. The domain rules
     * how a failed attempt reads and hands the reason back as data; this only writes that reason to the log,
     * so an operator debugging a failed update can still find the cause, and pushes the settled outcome.
     * Announcing is unconditional on purpose: an event that never comes leaves the Explorer waiting forever.
     */
    private void settle(ContainerUpdate update, SshTarget target) {
        Settlement settlement = update.carryOut(target, sshCommands, hostKeys);
        logSettled(update, settlement);
        // Retire the stale verdict BEFORE announcing: the Explorer re-reads its containers when the settled
        // event arrives, so announcing first would hand it the mark this update just resolved.
        update.forgetOutdatedVerdict(settlement.outcome(), snapshotStore);
        update.announce(settlement, forPublishingEvents);
    }

    /**
     * One line per settled update, naming the machine, the container and the outcome — and the host's own
     * words when there are any.
     *
     * <p>An update recreates a running container on somebody's machine. It is the most destructive thing
     * Fjord does to one, and it left no trace at all: an operator asking their Fjord's log whether an
     * update had even been attempted found nothing, and a failed one said nothing about why. That is the
     * audit trail, not chatter. The level follows the outcome, so a fleet's failed updates can be found
     * without reading its successful ones.
     */
    private void logSettled(ContainerUpdate update, Settlement settlement) {
        String reason = settlement.diagnostic() == null ? "" : " — " + settlement.diagnostic();
        if (settlement.outcome().updated()) {
            log.info("Update of container {} on machine {} settled {}{}", update.containerName(),
                update.machineId().value(), settlement.outcome(), reason);
        } else {
            log.warn("Update of container {} on machine {} settled {}{}", update.containerName(),
                update.machineId().value(), settlement.outcome(), reason);
        }
    }

    /**
     * Every container Fjord has scraped from the machine {@code machineId} — the Fjord server's own, a
     * server peer's, or a LAN server's. Read from what Fjord already knows rather than scraped afresh: the
     * scheduler refreshes all three every 30 seconds, and an update is not worth making an operator wait
     * on a fleet-wide scrape. A machine Fjord knows nothing about simply has no containers, and the
     * domain's "no container of that name" refusal covers it.
     */
    private List<DockerService> containersOn(MachineId machineId) {
        List<DockerService> containers = new ArrayList<>();
        if (machineId.equals(fjordServerIdentity.identity())) {
            containers.addAll(fjordServerContainers.discover());
        }
        peerContainers.discoverAll().stream()
            .filter(peer -> machineId.value().equals(peer.machineId()))
            .forEach(peer -> containers.addAll(peer.containers()));
        lanServerScrape.getLanServerContainers().stream()
            .filter(lanServer -> machineId.value().equals(lanServer.machineId()))
            .forEach(lanServer -> containers.addAll(lanServer.containers()));
        return containers;
    }

    @Override
    public List<PublishableService> getUnpublishedFjordServerServices(List<ReverseProxyRoute> existingRoutes) {
        // The catalogue-driven filtering over the cached Fjord-server snapshot lives in
        // InMemoryContainerSnapshotStore now; this use case delegates to its read port.
        return fjordServerDockerServices.getUnpublishedFjordServerServices(existingRoutes);
    }
}
