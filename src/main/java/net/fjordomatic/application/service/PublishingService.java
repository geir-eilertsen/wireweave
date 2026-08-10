package net.fjordomatic.application.service;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.DeletePublishedServiceUseCase;
import net.fjordomatic.application.GetLaunchpadServicesUseCase;
import net.fjordomatic.application.GetPublishableServicesUseCase;
import net.fjordomatic.application.GetPublishedServicesUseCase;
import net.fjordomatic.application.IgnorePublishableServiceUseCase;
import net.fjordomatic.application.PublishLanServiceUseCase;
import net.fjordomatic.application.PublishPeerServiceUseCase;
import net.fjordomatic.application.PublishedServicesCacheInvalidator;
import net.fjordomatic.application.PublishingConstants;
import net.fjordomatic.application.RefreshLaunchpadVersionsUseCase;
import net.fjordomatic.application.UnignorePublishableServiceUseCase;
import net.fjordomatic.application.UpdatePublishedServiceUseCase;
import net.fjordomatic.config.ConfigResolver;
import net.fjordomatic.domain.AccessEntry;
import net.fjordomatic.domain.FjordHostnames;
import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.LanAnchor;
import net.fjordomatic.domain.LanServer;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.LaunchpadVisibility;
import net.fjordomatic.domain.PublishableService;
import net.fjordomatic.domain.PublishableService.PublishableSource;
import net.fjordomatic.domain.Reachability;
import net.fjordomatic.domain.ReverseProxyRoute;
import net.fjordomatic.domain.Server;
import net.fjordomatic.domain.VpnClient;
import net.fjordomatic.domain.port.ForCheckingLanReachability;
import net.fjordomatic.domain.port.ForDiscoveringLanServerContainers;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers;
import net.fjordomatic.domain.port.ForDiscoveringFjordServerContainers;
import net.fjordomatic.domain.port.ForGettingLanServerScrape;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.fjordomatic.domain.port.ForGettingServerInfo;
import net.fjordomatic.domain.port.ForGettingFjordServerDockerServices;
import net.fjordomatic.domain.port.ForGettingVpnClients;
import net.fjordomatic.domain.port.ForManagingIgnoredServices;
import net.fjordomatic.domain.port.ForPersistingLanServers;
import net.fjordomatic.domain.port.ForPersistingReverseProxyRoutes;
import net.fjordomatic.domain.port.ForProbingServiceVersion;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.domain.port.ForResolvingPeerIds;
import net.fjordomatic.domain.port.ForResolvingServerLanCidr;
import net.fjordomatic.domain.port.ForResolvingServiceGroup;
import net.fjordomatic.domain.port.ForResolvingFjordServerIdentity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class PublishingService implements
    GetPublishedServicesUseCase,
    GetLaunchpadServicesUseCase,
    PublishedServicesCacheInvalidator,
    PublishPeerServiceUseCase,
    PublishLanServiceUseCase,
    DeletePublishedServiceUseCase,
    GetPublishableServicesUseCase,
    UpdatePublishedServiceUseCase,
    IgnorePublishableServiceUseCase,
    UnignorePublishableServiceUseCase,
    RefreshLaunchpadVersionsUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForGettingServerInfo forGettingServerInfo;
    private final ForGettingVpnClients forGettingVpnClients;
    private final ForResolvingPeerIds forResolvingPeerIds;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ConfigResolver configResolver;
    private final ForPublishingEvents forPublishingEvents;
    private final ForManagingIgnoredServices forManagingIgnoredServices;
    private final PendingPublicationsService pendingPublicationsService;
    private final ForDiscoveringPeerContainers forDiscoveringPeerContainers;
    private final ForDiscoveringFjordServerContainers forDiscoveringFjordServerContainers;
    private final ForGettingLanServerScrape forGettingLanServerScrape;
    private final ForGettingFjordServerDockerServices forGettingFjordServerDockerServices;
    private final ForProbingServiceVersion forProbingServiceVersion;
    private final ForCheckingLanReachability forCheckingLanReachability;
    private final ForResolvingServiceGroup forResolvingServiceGroup;
    // The Fjord server's own identity — the one machine that appears in no store, so it cannot be found
    // by searching for it. Needed to attribute a hub route to the machine it actually runs on.
    private final ForResolvingFjordServerIdentity fjordServerIdentity;

    private volatile List<PublishedServiceUco> cache = null;

    long traefikActivationTimeoutMillis = 15_000;
    long traefikActivationRetryIntervalMillis = 500;

    private record PendingState(boolean requiresAuth) {}

    private final Map<String, PendingState> pendingPublishes = new ConcurrentHashMap<>();

    public PublishingService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                             ForGettingServerInfo forGettingServerInfo,
                             ForGettingVpnClients forGettingVpnClients,
                             ForResolvingPeerIds forResolvingPeerIds,
                             ForGettingPeerConfigurations forGettingPeerConfigurations,
                             ForResolvingServerLanCidr forResolvingServerLanCidr,
                             ForPersistingLanServers forPersistingLanServers,
                             ConfigResolver configResolver,
                             ForPublishingEvents forPublishingEvents,
                             ForManagingIgnoredServices forManagingIgnoredServices,
                             PendingPublicationsService pendingPublicationsService,
                             ForDiscoveringPeerContainers forDiscoveringPeerContainers,
                             ForDiscoveringFjordServerContainers forDiscoveringFjordServerContainers,
                             ForGettingLanServerScrape forGettingLanServerScrape,
                             ForGettingFjordServerDockerServices forGettingFjordServerDockerServices,
                             ForProbingServiceVersion forProbingServiceVersion,
                             ForCheckingLanReachability forCheckingLanReachability,
                             ForResolvingServiceGroup forResolvingServiceGroup,
                             ForResolvingFjordServerIdentity fjordServerIdentity) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forGettingServerInfo = forGettingServerInfo;
        this.forGettingVpnClients = forGettingVpnClients;
        this.forResolvingPeerIds = forResolvingPeerIds;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forPersistingLanServers = forPersistingLanServers;
        this.configResolver = configResolver;
        this.forPublishingEvents = forPublishingEvents;
        this.forManagingIgnoredServices = forManagingIgnoredServices;
        this.pendingPublicationsService = pendingPublicationsService;
        this.forDiscoveringPeerContainers = forDiscoveringPeerContainers;
        this.forDiscoveringFjordServerContainers = forDiscoveringFjordServerContainers;
        this.forGettingLanServerScrape = forGettingLanServerScrape;
        this.forGettingFjordServerDockerServices = forGettingFjordServerDockerServices;
        this.forProbingServiceVersion = forProbingServiceVersion;
        this.forCheckingLanReachability = forCheckingLanReachability;
        this.forResolvingServiceGroup = forResolvingServiceGroup;
        this.fjordServerIdentity = fjordServerIdentity;
    }

    @Override
    public void invalidatePublishedServicesCache() {
        cache = null;
    }

    @Override
    public List<PublishedServiceUco> getPublishedServices() {
        if (cache != null) return cache;

        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (routes.isEmpty()) return List.of();

        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<DockerService> localServices = forGettingServerInfo.getServicesWithExposedPorts(Server.fjordServer());
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        Map<String, Reachability> lanReachabilities = forCheckingLanReachability.snapshot();
        // Same enrichment as the launchpad: a route backed by a discoverable container surfaces
        // the container's image + version, and a configured versionEndpoint (probed periodically)
        // overrides the container's version (#245).
        ContainerImageSnapshot images = currentContainerImages();
        Map<String, String> probedVersions = launchpadVersions;

        cache = routes.stream()
            .filter(r -> !isInfrastructureRouter(r))
            .filter(r -> !r.isOauth2EndpointsRouter())
            .map(r -> toUco(r, vpnClients, localServices, serverLanCidr, lanReachabilities,
                images, probedVersions))
            .toList();
        return cache;
    }

    private boolean isInfrastructureRouter(ReverseProxyRoute route) {
        return PublishingConstants.isMandatory(route.getDomainName(), configResolver.getDomain());
    }

    @Override
    public List<LaunchpadServiceUco> getLaunchpadServices(String callerIp, AccessEntry viewer) {
        List<PublishedServiceUco> published = getPublishedServices();
        if (published.isEmpty()) return List.of();

        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        List<VpnClient> vpnClients = forGettingVpnClients.getClients();
        List<ReverseProxyRoute> routes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        String baseDomain = configResolver.getDomain();
        ContainerImageSnapshot images = currentContainerImages();
        Map<String, String> probedVersions = launchpadVersions;
        // Match each enriched Uco back to its route by (dnsAddress, pathPrefix), then ask the
        // domain for the consolidated launchpad visibility, tile label, and backing container.
        // NOT_VISIBLE entries are dropped; the rest carry the tri-state, display name, and — when
        // a container backs the route — its running Docker image/version through so the launchpad
        // client doesn't have to know why a service is shown, how it's labelled, or what runs it.
        return published.stream()
            .flatMap(s -> ReverseProxyRoute
                .findByFqdnAndPath(routes, s.dnsAddress(), s.pathPrefix())
                .map(r -> {
                    LaunchpadVisibility visibility = r.launchpadVisibility(s.state(), viewer, forResolvingServiceGroup);
                    if (visibility == LaunchpadVisibility.NOT_VISIBLE) return null;
                    DockerService backing = r.backingContainer(images.fjordServerContainers(),
                        images.peerContainersByVpnIp(), images.lanServerContainersByAddress()).orElse(null);
                    // A configured version endpoint (a service running natively on a LAN machine,
                    // reporting its own version over HTTP) takes precedence over a container's
                    // image tag; the image still comes only from a backing container, if any.
                    String probedVersion = r.hasVersionEndpoint()
                        ? probedVersions.get(r.getName()) : null;
                    return new LaunchpadServiceUco(s.dnsAddress(), s.pathPrefix(), s.hostAddress(),
                        visibility, r.launchpadLiveness(s.state()),
                        r.launchpadUrl(callerIp, peers, vpnClients, baseDomain),
                        r.launchpadDisplayName(baseDomain), r.subdomain(baseDomain),
                        r.launchpadIconQuery(),
                        r.hostDisplayName(vpnClients, forResolvingPeerIds, peers),
                        backing == null ? null : backing.image(),
                        probedVersion != null ? probedVersion
                            : (backing == null ? null : backing.version()));
                })
                .filter(java.util.Objects::nonNull)
                .stream())
            .toList();
    }

    private record ContainerImageSnapshot(
        List<DockerService> fjordServerContainers,
        Map<String, List<DockerService>> peerContainersByVpnIp,
        Map<String, List<DockerService>> lanServerContainersByAddress) {}

    /**
     * Assembles the discovered-container view used to resolve each route's backing image
     * (issue #210). Cheap to build per request: peer and LAN containers are served from the
     * state-refresh caches, and only the local Docker socket is read live (sub-millisecond).
     */
    private ContainerImageSnapshot currentContainerImages() {
        Map<String, List<DockerService>> peerContainers = forDiscoveringPeerContainers.discoverAll().stream()
            .filter(p -> p.vpnIp() != null)
            .collect(java.util.stream.Collectors.toMap(
                ForDiscoveringPeerContainers.PeerContainers::vpnIp,
                ForDiscoveringPeerContainers.PeerContainers::containers,
                (a, b) -> a));
        Map<String, List<DockerService>> lanServerContainers = forGettingLanServerScrape
            .getLanServerContainers().stream()
            .filter(h -> h.lanAddress() != null)
            .collect(java.util.stream.Collectors.toMap(
                ForDiscoveringLanServerContainers.LanServerContainers::lanAddress,
                ForDiscoveringLanServerContainers.LanServerContainers::containers,
                (a, b) -> a));
        return new ContainerImageSnapshot(
            forDiscoveringFjordServerContainers.discover(),
            peerContainers, lanServerContainers);
    }

    /**
     * Router name → probed version, for every route with a configured version endpoint
     * (issue #210). Served to the launchpad as a plain cache read; populated only by
     * {@link #refreshLaunchpadVersions()}, which the state-refresh scheduler drives.
     */
    private volatile Map<String, String> launchpadVersions = Map.of();

    @Override
    public void refreshLaunchpadVersions() {
        // The route owns the probe — it talks to the ForProbingServiceVersion driven port itself
        // (see ReverseProxyRoute#probeVersion). This service only orchestrates: pick the routes,
        // run the probes concurrently, cache the result. It must not call the port directly.
        List<CompletableFuture<Map.Entry<String, String>>> probes =
            forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .filter(ReverseProxyRoute::hasVersionEndpoint)
                .map(r -> CompletableFuture.supplyAsync(() ->
                    r.probeVersion(forProbingServiceVersion)
                        .map(v -> Map.entry(r.getName(), v))
                        .orElse(null)))
                .toList();
        launchpadVersions = probes.stream()
            .map(CompletableFuture::join)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private PublishedServiceUco toUco(ReverseProxyRoute route,
                                    List<VpnClient> vpnClients, List<DockerService> localServices,
                                    String serverLanCidr, Map<String, Reachability> lanReachabilities,
                                    ContainerImageSnapshot images, Map<String, String> probedVersions) {
        var peers = forGettingPeerConfigurations.getAllPeerConfigs();
        var lanServers = forPersistingLanServers.getAll();
        Server.State hostState = route.hostState(localServices, vpnClients, peers, serverLanCidr, lanReachabilities);
        String baseDomain = configResolver.getDomain();
        DockerService backing = route.backingContainer(images.fjordServerContainers(),
            images.peerContainersByVpnIp(), images.lanServerContainersByAddress()).orElse(null);
        String probedVersion = route.hasVersionEndpoint() ? probedVersions.get(route.getName()) : null;
        return new PublishedServiceUco(
            route.displayName(baseDomain, localServices, vpnClients, forResolvingPeerIds, peers),
            route.shortName(baseDomain, vpnClients, forResolvingPeerIds, peers),
            // Whose machine this service is on, as an identity. The browser used to work it out from the
            // display name, which put a service under the wrong machine card whenever two names agreed.
            route.hostMachineId(peers, lanServers, fjordServerIdentity.identity())
                .map(MachineId::value).orElse(null),
            route.hostDisplayName(vpnClients, forResolvingPeerIds, peers),
            route.lanServerName(lanServers).orElse(null),
            route.serviceLocation(vpnClients, forResolvingPeerIds, peers),
            hostState == Server.State.OK,
            route.getDomainName(),
            route.getAddress(),
            route.getPort(),
            hostState,
            route.getAuthInfo() != null,
            route.getRootRedirectPath(),
            route.isDirectUrlDisabled(),
            route.isLanService(),
            route.getPathPrefix(),
            route.isHiddenFromLaunchpad(),
            route.getLaunchpadAlias(),
            route.getVersionEndpoint(),
            route.getVersionProperty(),
            backing == null ? null : backing.image(),
            probedVersion != null ? probedVersion : (backing == null ? null : backing.version()),
            route.authMode().wireValue()
        );
    }

    // --- PublishPeerServiceUseCase ---

    @Override
    public void publishService(String address, int port, String subdomain, boolean requiresAuth,
                               String rootRedirectPath, boolean directUrlDisabled, String pathPrefix) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);

        // A LAN docker host's IP arrives here when the user clicks "+ Publish" on a discovered
        // LAN_SERVER service. Dispatch to the LAN flow so the route is marked isLanService=true
        // and the dashboard's direct-LAN URL bypass works (#180). The address may be on a relay
        // peer's LAN or in the Fjord server's own subnet (server LAN CIDR).
        if (hostInsideAnyLanCidr(address)) {
            log.info("Address {} falls inside a relay peer's or the Fjord server's LAN CIDR — publishing as LAN service", address);
            publishLanRoute(subdomain, address, port, "http", requiresAuth, directUrlDisabled, rootRedirectPath, normalisedPath);
            return;
        }

        String fqdn = subdomain + "." + configResolver.getDomain();

        List<ReverseProxyRoute> existing = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.conflictsWithExisting(existing, fqdn, normalisedPath)) {
            throw new IllegalArgumentException(
                "A route already exists on " + fqdn +
                (normalisedPath == null ? " (host-only)" : " for path " + normalisedPath));
        }

        log.info("Publishing service: {} -> {}:{} (auth: {}, directUrlDisabled: {}, pathPrefix: {})",
            fqdn, address, port, requiresAuth, directUrlDisabled, normalisedPath);

        pendingPublishes.put(subdomain, new PendingState(requiresAuth));
        pendingPublicationsService.track(address, port);

        // Publishing is one step now: write the Traefik route. The name already resolves — the
        // operator's single *.<domain> record answers for it (#331) — so there is nothing to create
        // and nothing to wait for propagating. Still async: waiting for Traefik to pick the route up
        // is real, and the browser gets the pending state immediately.
        CompletableFuture.runAsync(() -> activate(subdomain, fqdn, address, port, requiresAuth, rootRedirectPath, directUrlDisabled, normalisedPath));
    }

    @Override
    public PublishStatus getPublishStatus(String subdomain) {
        String fqdn = subdomain + "." + configResolver.getDomain();
        boolean traefikActive = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .anyMatch(r -> r.getDomainName().equals(fqdn));
        if (traefikActive) {
            pendingPublishes.remove(subdomain);
            return new PublishStatus(true);
        }
        return new PublishStatus(false);
    }

    @Override
    public List<PendingPublication> getPendingPublications() {
        return pendingPublishes.entrySet().stream()
            .map(e -> new PendingPublication(e.getKey(), e.getValue().requiresAuth()))
            .toList();
    }

    // --- PublishLanServiceUseCase ---

    @Override
    public void publishLanService(String subdomain, MachineId machineId, int port, String protocol,
                                  boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                                  String pathPrefix) {
        LanServer machine = LanServer.findById(machineId, forPersistingLanServers.getAll())
            .orElseThrow(() -> new IllegalArgumentException("Unknown machine: " + machineId.value()));
        publishLanRoute(subdomain, machine.lanAddress(), port, protocol,
            requiresAuth, directUrlDisabled, rootRedirectPath, pathPrefix);
    }

    private void publishLanRoute(String subdomain, String host, int port, String protocol,
                                 boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                                 String pathPrefix) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        ReverseProxyRoute.validateForPublication(subdomain + "." + configResolver.getDomain(), host, port);
        String scheme = ReverseProxyRoute.normaliseProtocol(protocol);
        ReverseProxyRoute.validateProtocol(scheme);

        if (!hostInsideAnyLanCidr(host)) {
            throw new IllegalArgumentException(
                "Target host " + host + " is not inside any relay peer's lanCidr, " +
                "nor inside the Fjord server's own LAN CIDR. Set lanCidr on a relay peer first " +
                "(or, on EC2, the server LAN CIDR is auto-detected from instance metadata).");
        }

        String fqdn = subdomain + "." + configResolver.getDomain();

        List<ReverseProxyRoute> existing = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        if (ReverseProxyRoute.conflictsWithExisting(existing, fqdn, normalisedPath)) {
            throw new IllegalArgumentException(
                "A route already exists on " + fqdn +
                (normalisedPath == null ? " (host-only)" : " for path " + normalisedPath));
        }

        log.info("Publishing LAN service: {} -> {}://{}:{} (auth: {}, directUrlDisabled: {}, rootRedirectPath: {}, pathPrefix: {})",
            fqdn, scheme, host, port, requiresAuth, directUrlDisabled, rootRedirectPath, normalisedPath);

        pendingPublishes.put(subdomain, new PendingState(requiresAuth));

        CompletableFuture.runAsync(() ->
            activateLan(subdomain, fqdn, host, port, scheme, requiresAuth, directUrlDisabled, rootRedirectPath, normalisedPath));
    }

    private boolean hostInsideAnyLanCidr(String host) {
        return LanAnchor.resolve(host,
            forGettingPeerConfigurations.getAllPeerConfigs(),
            forResolvingServerLanCidr.resolve().orElse(null)).isPresent();
    }

    /**
     * Writes the Traefik LAN route and waits for Traefik to pick it up. No DNS step: the name already
     * resolves under the operator's one wildcard record (#331).
     */
    void activateLan(String subdomain, String fqdn, String host, int port, String protocol,
                     boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                     String pathPrefix) {
        try {
            forPersistingReverseProxyRoutes.addLanReverseProxyRoute(
                fqdn, host, port, protocol, requiresAuth, directUrlDisabled, rootRedirectPath, pathPrefix);
        } catch (Exception e) {
            log.error("Failed to write Traefik LAN route for {}: {}", fqdn, e.getMessage(), e);
            rollbackLan(subdomain, fqdn, false, pathPrefix);
            return;
        }
        if (!waitForTraefikRoute(fqdn)) {
            log.warn("Traefik did not pick up LAN route for {}; rolling back", fqdn);
            rollbackLan(subdomain, fqdn, true, pathPrefix);
            return;
        }
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-traefik-active", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    /** Undoes a LAN publish. Only ever reached because Traefik failed — there is no DNS half left. */
    private void rollbackLan(String subdomain, String fqdn, boolean removeRoute, String pathPrefix) {
        if (removeRoute) {
            try {
                ReverseProxyRoute.findByFqdnAndPath(
                        forPersistingReverseProxyRoutes.getReverseProxyRoutes(), fqdn, pathPrefix)
                    .ifPresentOrElse(
                        r -> forPersistingReverseProxyRoutes.deleteReverseProxyRoute(r.getName()),
                        () -> forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn));
            } catch (Exception e) {
                log.warn("Failed to remove Traefik route during LAN rollback for {}: {}", fqdn, e.getMessage());
            }
        }
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-rolled-back", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    /**
     * Writes the Traefik route and waits for Traefik to pick it up. No DNS step: the name already
     * resolves under the operator's one wildcard record (#331), and Let's Encrypt's HTTP-01 challenge
     * can run the moment the route exists.
     */
    void activate(String subdomain, String fqdn, String address, int port, boolean requiresAuth,
                  String rootRedirectPath, boolean directUrlDisabled, String pathPrefix) {
        String persistedAddress = forGettingServerInfo.findContainerNameByIp(Server.fjordServer(), address).orElse(address);
        if (!persistedAddress.equals(address)) {
            log.info("Normalized backend address {} -> {} for {}", address, persistedAddress, fqdn);
        }
        try {
            forPersistingReverseProxyRoutes.addReverseProxyRoute(fqdn, persistedAddress, port, requiresAuth, rootRedirectPath, pathPrefix);
        } catch (Exception e) {
            log.error("Failed to write Traefik route for {}: {}", fqdn, e.getMessage(), e);
            rollback(subdomain, fqdn, address, port, false, pathPrefix);
            return;
        }
        if (directUrlDisabled) {
            forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(fqdn, pathPrefix, true);
        }
        log.info("Created Traefik route for {}", fqdn);
        if (!waitForTraefikRoute(fqdn)) {
            log.warn("Traefik did not pick up route for {}; rolling back", fqdn);
            rollback(subdomain, fqdn, address, port, true, pathPrefix);
            return;
        }
        pendingPublicationsService.untrack(address, port);
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-traefik-active", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    /** Undoes a publish. Only ever reached because Traefik failed — there is no DNS half left. */
    private void rollback(String subdomain, String fqdn, String address, int port, boolean removeRoute, String pathPrefix) {
        if (removeRoute) {
            try {
                ReverseProxyRoute.findByFqdnAndPath(
                        forPersistingReverseProxyRoutes.getReverseProxyRoutes(), fqdn, pathPrefix)
                    .ifPresentOrElse(
                        r -> forPersistingReverseProxyRoutes.deleteReverseProxyRoute(r.getName()),
                        () -> forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn));
            } catch (Exception e) {
                log.warn("Failed to remove Traefik route during rollback for {}: {}", fqdn, e.getMessage());
            }
        }
        pendingPublicationsService.untrack(address, port);
        pendingPublishes.remove(subdomain);
        invalidatePublishedServicesCache();
        forPublishingEvents.publish("published-services", "publish-rolled-back", subdomain);
        forPublishingEvents.publish("published-services", "service-updated", subdomain);
    }

    private boolean waitForTraefikRoute(String fqdn) {
        long deadline = System.currentTimeMillis() + traefikActivationTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean active = forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                .anyMatch(r -> r.getDomainName().equals(fqdn));
            if (active) {
                log.info("Traefik picked up route for {}", fqdn);
                return true;
            }
            log.debug("Waiting for Traefik to pick up route for {}", fqdn);
            try { Thread.sleep(traefikActivationRetryIntervalMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    // --- DeletePublishedServiceUseCase ---

    @Override
    public void deleteService(String fqdn, String pathPrefix) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);

        if (PublishingConstants.isMandatory(fqdn, configResolver.getDomain())) {
            throw new IllegalArgumentException("Cannot delete built-in service: " + fqdn);
        }
        log.info("Deleting service: {} (pathPrefix: {})", fqdn, normalisedPath);

        if (normalisedPath == null) {
            // Legacy host-only delete: remove all routes via the dnsName-keyed helper, which
            // resolves to the conventional <fqdn>-router name. Preserves prior behaviour.
            forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(fqdn);
        } else {
            // Path-based delete: find the specific route and remove only it.
            List<ReverseProxyRoute> existing = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
            ReverseProxyRoute target = ReverseProxyRoute.findByFqdnAndPath(existing, fqdn, normalisedPath)
                .orElseThrow(() -> new IllegalArgumentException(
                    "No route found for " + fqdn + " with pathPrefix " + normalisedPath));
            forPersistingReverseProxyRoutes.deleteReverseProxyRoute(target.getName());
        }
        log.info("Deleted Traefik route for {} ({})", fqdn, normalisedPath);

        waitForTraefikRouteDeletion(fqdn, normalisedPath);

        // Nothing else to undo: unpublishing removes the Traefik route and stops there. The name goes
        // on resolving under the operator's wildcard record, which is theirs and not Fjord's (#331).
        invalidatePublishedServicesCache();
    }

    private void waitForTraefikRouteDeletion(String fqdn, String pathPrefix) {
        long deadline = System.currentTimeMillis() + 15_000;
        int consecutiveAbsent = 0;
        while (System.currentTimeMillis() < deadline) {
            // For host-only delete, "absent" means no route on this fqdn at all. For path-based
            // delete, "absent" means no route on this (fqdn, pathPrefix). The domain knows what
            // route uniqueness means.
            boolean stillPresent = pathPrefix == null
                ? forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
                    .anyMatch(r -> r.getDomainName().equals(fqdn))
                : ReverseProxyRoute.findByFqdnAndPath(
                    forPersistingReverseProxyRoutes.getReverseProxyRoutes(), fqdn, pathPrefix).isPresent();
            if (!stillPresent) {
                consecutiveAbsent++;
                if (consecutiveAbsent >= 2) {
                    log.info("Traefik confirmed route deletion for {} ({})", fqdn, pathPrefix);
                    return;
                }
            } else {
                consecutiveAbsent = 0;
            }
            log.debug("Waiting for Traefik to remove route for {} ({})", fqdn, pathPrefix);
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("Traefik did not remove route for {} within 15s, proceeding anyway", fqdn);
    }

    // --- GetPublishableServicesUseCase ---

    @Override
    public List<PublishableService> getPublishableServices() {
        var existingRoutes = forPersistingReverseProxyRoutes.getReverseProxyRoutes();
        var publishable = new ArrayList<PublishableService>();

        forDiscoveringPeerContainers.discoverAll().stream()
            .filter(peer -> "OK".equals(peer.status()))
            .flatMap(peer -> peer.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> !p.isRange())
                    .filter(p -> p.publicPort() != null)
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(peer.vpnIp()) && r.getPort() == p.publicPort()))
                    .filter(p -> !pendingPublicationsService.isPending(peer.vpnIp(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.PEER, peer.machineId(), peer.peerId(),
                        peer.vpnIp(), container.containerName(), p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        forGettingLanServerScrape.getLanServerContainers().stream()
            .filter(host -> "OK".equals(host.status()))
            .flatMap(host -> host.containers().stream()
                .flatMap(container -> container.ports().stream()
                    .filter(p -> "tcp".equals(p.type()))
                    .filter(p -> !p.isRange())
                    .filter(p -> p.publicPort() != null)
                    .filter(p -> existingRoutes.stream()
                        .noneMatch(r -> r.getAddress().equals(host.lanAddress()) && r.getPort() == p.publicPort()))
                    .filter(p -> !pendingPublicationsService.isPending(host.lanAddress(), p.publicPort()))
                    .map(p -> new PublishableService(PublishableSource.LAN_SERVER, host.machineId(),
                        host.relayPeerName(), host.lanAddress(), container.containerName(),
                        p.publicPort(), null, false))
                )
            )
            .forEach(publishable::add);

        forGettingFjordServerDockerServices.getUnpublishedFjordServerServices(existingRoutes).stream()
            .filter(s -> !pendingPublicationsService.isPending(s.address(), s.port()))
            .forEach(publishable::add);

        Set<String> ignoredKeys = forManagingIgnoredServices.getIgnoredServiceKeys();
        return publishable.stream().distinct()
            .map(s -> new PublishableService(s.source(), s.machineId(), s.peerId(), s.address(),
                s.containerName(), s.port(), s.rootRedirectPath(), ignoredKeys.contains(s.ignoreKey())))
            .toList();
    }

    // --- UpdatePublishedServiceUseCase ---

    @Override
    public void updateService(String dnsName, String pathPrefix, PublishedServicePatch patch) {
        String normalisedPath = ReverseProxyRoute.normalisePathPrefix(pathPrefix);
        ReverseProxyRoute.validatePathPrefix(normalisedPath);
        if (PublishingConstants.isMandatory(dnsName, configResolver.getDomain())) {
            throw new IllegalArgumentException("Cannot edit built-in service: " + dnsName);
        }
        log.info("Updating {} ({}): {}", dnsName, normalisedPath, patch);

        // authMode supersedes the legacy requiresAuth toggle; either may be set, not both from the UI.
        if (patch.authMode() != null) {
            forPersistingReverseProxyRoutes.setRouteAuthMode(
                dnsName, normalisedPath, net.fjordomatic.domain.AuthMode.fromString(patch.authMode()));
        } else if (patch.requiresAuth() != null) {
            forPersistingReverseProxyRoutes.setRouteAuthentication(dnsName, normalisedPath, patch.requiresAuth());
        }
        if (patch.directUrlDisabled() != null) {
            forPersistingReverseProxyRoutes.setRouteDirectUrlDisabled(dnsName, normalisedPath, patch.directUrlDisabled());
        }
        if (patch.hiddenFromLaunchpad() != null) {
            forPersistingReverseProxyRoutes.setRouteHiddenFromLaunchpad(dnsName, normalisedPath, patch.hiddenFromLaunchpad());
        }
        if (patch.rootRedirectPath() != null) {
            forPersistingReverseProxyRoutes.setRouteRootRedirectPath(
                dnsName, normalisedPath, blankToNull(patch.rootRedirectPath()));
        }
        if (patch.launchpadAlias() != null) {
            String alias = patch.launchpadAlias().isBlank() ? null : patch.launchpadAlias().trim();
            forPersistingReverseProxyRoutes.setRouteLaunchpadAlias(dnsName, normalisedPath, alias);
        }
        // versionEndpoint and versionProperty are paired: the persistence port accepts both at once,
        // so we call it when at least one is set, falling back to "blank means clear" for either side.
        if (patch.versionEndpoint() != null || patch.versionProperty() != null) {
            String endpoint = blankToNull(patch.versionEndpoint());
            String property = blankToNull(patch.versionProperty());
            forPersistingReverseProxyRoutes.setRouteVersionEndpoint(dnsName, normalisedPath, endpoint, property);
        }
        invalidatePublishedServicesCache();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // --- IgnorePublishableServiceUseCase ---

    @Override
    public void ignoreService(String key) {
        forManagingIgnoredServices.ignoreService(key);
    }

    // --- UnignorePublishableServiceUseCase ---

    @Override
    public void unignoreService(String key) {
        forManagingIgnoredServices.unignoreService(key);
    }
}
