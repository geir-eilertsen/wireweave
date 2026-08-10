package net.fjordomatic.application.service;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.AdoptDiscoveredMachineUseCase;
import net.fjordomatic.application.DeletePublishedServiceUseCase;
import net.fjordomatic.application.DeleteLanServerUseCase;
import net.fjordomatic.application.GenerateLanServerSetupScriptUseCase;
import net.fjordomatic.application.GetLanServersUseCase;
import net.fjordomatic.application.PublishedServicesCacheInvalidator;
import net.fjordomatic.application.RegisterLanServerUseCase;
import net.fjordomatic.application.RenameLanServerUseCase;
import net.fjordomatic.application.ResolveLanAnchorUseCase;
import net.fjordomatic.application.UpdateLanServerDescriptionUseCase;
import net.fjordomatic.application.UpdateLanServerDeviceCategoryUseCase;
import net.fjordomatic.domain.DeviceCategory;
import net.fjordomatic.domain.DiscoveredLanMachine;
import net.fjordomatic.domain.LanAnchor;
import net.fjordomatic.domain.LanServer;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.Machine;
import net.fjordomatic.domain.NotFoundException;
import net.fjordomatic.domain.ConflictException;
import net.fjordomatic.domain.LanServerSetupScript;
import net.fjordomatic.domain.ReverseProxyRoute;
import net.fjordomatic.domain.SshCredentialDraft;
import net.fjordomatic.domain.SshCredentialVerification;
import net.fjordomatic.domain.SshTarget;
import net.fjordomatic.domain.port.ForForgettingDiscoveredLanMachines;
import net.fjordomatic.domain.port.ForGettingDiscoveredLanMachines;
import net.fjordomatic.domain.port.ForGettingLanServers;
import net.fjordomatic.domain.port.ForGettingLanServers.LanServerView;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.fjordomatic.domain.port.ForPersistingHostCredentials;
import net.fjordomatic.domain.port.ForPersistingLanServers;
import net.fjordomatic.domain.port.ForPersistingReverseProxyRoutes;
import net.fjordomatic.domain.port.ForResolvingServerLanCidr;
import net.fjordomatic.domain.port.ForTrackingHostKeys;
import net.fjordomatic.domain.port.ForVerifyingSshCredentials;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@Slf4j
public class LanServerService implements
    RegisterLanServerUseCase,
    AdoptDiscoveredMachineUseCase,
    DeleteLanServerUseCase,
    RenameLanServerUseCase,
    UpdateLanServerDescriptionUseCase,
    UpdateLanServerDeviceCategoryUseCase,
    GetLanServersUseCase,
    GenerateLanServerSetupScriptUseCase,
    ResolveLanAnchorUseCase {

    private final ForPersistingLanServers forPersistingLanServers;
    private final ForGettingLanServers forGettingLanServers;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForGettingDiscoveredLanMachines forGettingDiscoveredLanMachines;
    private final ForForgettingDiscoveredLanMachines forForgettingDiscoveredLanMachines;
    private final ForVerifyingSshCredentials forVerifyingSshCredentials;

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    public LanServerService(ForPersistingLanServers forPersistingLanServers,
                            ForGettingLanServers forGettingLanServers,
                            ForGettingPeerConfigurations forGettingPeerConfigurations,
                            ForResolvingServerLanCidr forResolvingServerLanCidr,
                            ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                            // @Lazy breaks the construction-time bean cycle
                            // PublishingService -> ContainerService -> LanServerService (ForGettingLanServers)
                            // -> PublishingService (this cascade port). The cascade only invokes it at
                            // delete() time, so a lazy proxy is semantically safe.
                            @Lazy DeletePublishedServiceUseCase deletePublishedServiceUseCase,
                            // A published LAN service's lanServerName is a derived field cached in the
                            // published-services view, resolved by matching the route's address to a
                            // registered LAN server. Registering or renaming a server changes that
                            // mapping, so the cache must be dropped (#300). Same port the reachability
                            // service uses; @Lazy keeps it consistent with the cascade dependency above.
                            @Lazy PublishedServicesCacheInvalidator publishedServicesCacheInvalidator,
                            ForPersistingHostCredentials forPersistingHostCredentials,
                            ForTrackingHostKeys forTrackingHostKeys,
                            // The LAN-scan snapshot is owned by LanScannerService, which injects this
                            // service's ForGettingLanServers — so these two adoption ports would close a
                            // construction cycle. @Lazy is safe: both are touched only at adopt() time.
                            @Lazy ForGettingDiscoveredLanMachines forGettingDiscoveredLanMachines,
                            @Lazy ForForgettingDiscoveredLanMachines forForgettingDiscoveredLanMachines,
                            ForVerifyingSshCredentials forVerifyingSshCredentials) {
        this.forPersistingLanServers = forPersistingLanServers;
        this.forGettingLanServers = forGettingLanServers;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.deletePublishedServiceUseCase = deletePublishedServiceUseCase;
        this.publishedServicesCacheInvalidator = publishedServicesCacheInvalidator;
        this.forPersistingHostCredentials = forPersistingHostCredentials;
        this.forTrackingHostKeys = forTrackingHostKeys;
        this.forGettingDiscoveredLanMachines = forGettingDiscoveredLanMachines;
        this.forForgettingDiscoveredLanMachines = forForgettingDiscoveredLanMachines;
        this.forVerifyingSshCredentials = forVerifyingSshCredentials;
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort) {
        register(name, lanAddress, runsDocker, dockerPort, null, null);
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                         String description) {
        register(name, lanAddress, runsDocker, dockerPort, description, null);
    }

    @Override
    public void register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                         String description, DeviceCategory deviceCategory) {
        doRegister(name, lanAddress, runsDocker, dockerPort, description, deviceCategory);
    }

    /**
     * The shared registration path: validate, guard routability and name-uniqueness, persist, and
     * drop the published-services cache — returning the persisted {@link LanServer} so callers that
     * need the created machine (adoption) don't have to read it back. The public {@code register}
     * use case discards the return; {@link #adopt} keeps it.
     */
    private LanServer doRegister(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                                 String description, DeviceCategory deviceCategory) {
        // Normalise inputs up front so the persisted identity matches the (trimmed) uniqueness
        // comparison rule — mirrors LanServer.renamedTo, which also trims. (Trimming only strips
        // surrounding whitespace; it does not guarantee a URL-safe name.)
        String trimmedName = name == null ? null : name.trim();
        String trimmedAddress = lanAddress == null ? null : lanAddress.trim();
        LanServer.validate(trimmedName, trimmedAddress, runsDocker, dockerPort);
        // Read the peer configs and server LAN CIDR once (both are filesystem/metadata reads) and
        // reuse them for routability and the name-collision check.
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        if (LanAnchor.resolve(trimmedAddress, peers, serverLanCidr).isEmpty()) {
            throw new IllegalArgumentException(
                "lanAddress " + trimmedAddress + " is not inside any relay peer's lanCidr, " +
                "nor inside the Fjord server's own LAN CIDR. Set lanCidr on a relay peer first " +
                "(or, on EC2, the server LAN CIDR is auto-detected from instance metadata).");
        }
        // No name-collision guard (§6.22). It existed because save() upserted by NAME, so a duplicate
        // would silently overwrite a real machine — the store is keyed by MachineId now, so it does not,
        // and a name is free to be whatever an operator finds useful. Two sites may each have a "NAS".
        log.info("Registering LAN server: {} at {} (runsDocker={}, dockerPort={})",
            trimmedName, trimmedAddress, runsDocker, dockerPort);
        LanServer server =
            new LanServer(trimmedName, trimmedAddress, runsDocker, dockerPort, description, deviceCategory);
        forPersistingLanServers.save(server);
        // A route already pointing at this address now resolves to a named LAN server; drop the
        // cached published-services view so the new name surfaces (#300).
        publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
        return server;
    }

    @Override
    public RegistrationOutcome register(String name, String lanAddress, boolean runsDocker, Integer dockerPort,
                                        String description, DeviceCategory deviceCategory,
                                        SshCredentialDraft credential) {
        // Register first and independently: a bad credential must never roll back the registration.
        LanServer created = doRegister(name, lanAddress, runsDocker, dockerPort, description, deviceCategory);
        if (credential == null) {
            return new RegistrationOutcome(created, null, false);
        }
        SshCredentialVerification verification = verifyAndStoreCredential(created, credential);
        return new RegistrationOutcome(created, verification, verification.authenticated());
    }

    @Override
    public LanServer adopt(String ipAddress, String nameOverride) {
        return doAdopt(ipAddress, nameOverride);
    }

    @Override
    public AdoptionOutcome adopt(String ipAddress, String nameOverride, SshCredentialDraft credential) {
        // Register first and independently: a bad credential must never roll back the registration.
        LanServer created = doAdopt(ipAddress, nameOverride);
        SshCredentialVerification verification = verifyAndStoreCredential(created, credential);
        return new AdoptionOutcome(created, verification, verification.authenticated());
    }

    /**
     * The shared "verify a supplied credential against a just-registered machine, and store it only if it
     * authenticates" path — used by both adopting a scanned host and registering by address. Re-verifies
     * server-side against the machine's LAN address (nothing pinned yet — a freshly registered machine has
     * no host-key pin); the domain decides "did it authenticate?". A credential is stored keyed to the
     * machine's name only when it works, so the caller can report a separable outcome (registration always
     * stands; the credential may or may not have stuck).
     */
    private SshCredentialVerification verifyAndStoreCredential(LanServer created, SshCredentialDraft credential) {
        SshTarget target = credential.targetAt(created.lanAddress(), SshTarget.DEFAULT_PORT);
        SshCredentialVerification verification =
            SshCredentialVerification.probe(target, forVerifyingSshCredentials);
        if (verification.authenticated()) {
            forPersistingHostCredentials.save(credential.forMachine(created.machineId()));
            log.info("Stored the verified SSH credential for machine {}", forLog(created.name()));
        } else {
            log.info("Registered machine {} but did not store its SSH credential (reachable={}, authenticated={})",
                forLog(created.name()), verification.reachable(), verification.authenticated());
        }
        return verification;
    }

    /**
     * The shared adoption path used by both {@code adopt} overloads: read the candidate from the scan
     * snapshot (driven port), let the domain derive every registerable field (adoptionProfile /
     * chosenName), register through the shared registration path, then forget the candidate (driven
     * port) so it stops surfacing as discovered.
     */
    private LanServer doAdopt(String ipAddress, String nameOverride) {
        DiscoveredLanMachine candidate = forGettingDiscoveredLanMachines.findByIpAddress(ipAddress)
            .orElseThrow(() -> new NotFoundException("No discovered machine at " + ipAddress));
        DiscoveredLanMachine.AdoptionProfile profile = candidate.adoptionProfile();
        LanServer created = doRegister(profile.chosenName(nameOverride), profile.lanAddress(),
            profile.runsDocker(), profile.dockerPort(), null, profile.deviceCategory());
        forForgettingDiscoveredLanMachines.forget(ipAddress);
        return created;
    }

    @Override
    public void updateDeviceCategory(MachineId machineId, String deviceCategory) {
        // Validate the override value first: a non-blank value must be a valid DeviceCategory
        // (IllegalArgumentException -> 400). Null/blank parses to null = "clear the override".
        // The domain owns the parse rule; withDeviceCategory owns carrying everything else over.
        DeviceCategory parsed = DeviceCategory.fromString(deviceCategory);
        LanServer existing = requireById(machineId);
        forPersistingLanServers.save(existing.withDeviceCategory(parsed));
        log.info("Updated device category for LAN server {} to {}", forLog(existing.name()), parsed);
    }

    @Override
    public void updateDescription(MachineId machineId, String description) {
        // withDescription owns the normalisation rule; the service only finds the entry and saves.
        LanServer existing = requireById(machineId);
        forPersistingLanServers.save(existing.withDescription(description));
        log.info("Updated description for LAN server {}", forLog(existing.name()));
    }

    @Override
    public void delete(MachineId machineId) {
        LanServer existing = requireById(machineId);
        log.info("Deleting LAN server: {}", forLog(existing.name()));
        // Cascade first: a LAN server's published services are keyed on its lanAddress (LAN routes
        // are published via host.lanAddress()), so without this they'd be orphaned. Mirrors
        // VpnService.deletePeer cascading into published-service deletion via the *UseCase port.
        deletePublishedServicesFor(existing);
        forPersistingLanServers.deleteById(machineId);
    }

    /**
     * The LAN server with this identity, or a 404. The one lookup every write goes through, so a request
     * naming a machine that has left the fleet fails where it is asked rather than part-way through a
     * cascade.
     */
    private LanServer requireById(MachineId machineId) {
        return LanServer.findById(machineId, forPersistingLanServers.getAll())
            .orElseThrow(() -> new NotFoundException("LAN server not found: " + machineId.value()));
    }

    private void deletePublishedServicesFor(LanServer server) {
        String lanAddress = server.lanAddress();
        if (lanAddress == null || lanAddress.isBlank()) return;
        forPersistingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .filter(ReverseProxyRoute::isFjordManaged)
            .filter(route -> lanAddress.equals(route.getAddress()))
            .forEach(route -> {
                log.info("Deleting published service {} (path: {}) pointing to LAN server {}",
                    route.getDomainName(), route.getPathPrefix(), forLog(server.name()));
                deletePublishedServiceUseCase.deleteService(route.getDomainName(), route.getPathPrefix());
            });
    }

    @Override
    public void rename(MachineId machineId, String newName) {
        // The naming rule and the renamed-copy live on the LanServer entity; the service only
        // orchestrates the lookup, the collision guard and the persistence calls.
        List<LanServer> all = forPersistingLanServers.getAll();
        LanServer existing = LanServer.findById(machineId, all)
            .orElseThrow(() -> new NotFoundException("LAN server not found: " + machineId.value()));

        LanServer renamed = existing.renamedTo(newName);

        if (renamed.hasName(existing.name())) {
            log.info("Rename no-op: LAN server {} already has that name", forLog(existing.name()));
            return;
        }
        // save() upserts by identity, so the renamed copy simply replaces the entry it came from — and
        // the new name need not be free of anything, because nothing is keyed to it.
        forPersistingLanServers.save(renamed);
        // The published-services view caches each LAN route's resolved lanServerName; the rename
        // changed it, so drop the cache or the renamed machine card serves stale (old-name) data
        // and appears to lose its services until the name is changed back (#300).
        publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
        log.info("Renamed LAN server {} to {}", forLog(existing.name()), renamed.name());
    }

    @Override
    public List<LanServerView> getAll() {
        // The view assembly is a pure read over three driven ports and now lives in
        // LanServerViewAdapter; this use case just delegates to it (service -> driven port).
        return forGettingLanServers.getAll();
    }

    @Override
    public Optional<LanAnchor> resolveLanAnchor(String lanAddress) {
        if (lanAddress == null || lanAddress.isBlank()) return Optional.empty();
        return LanAnchor.resolve(lanAddress,
            forGettingPeerConfigurations.getAllPeerConfigs(),
            forResolvingServerLanCidr.resolve().orElse(null));
    }

    @Override
    public Optional<String> generateSetupScript(MachineId machineId) {
        // Orchestration only: read the LAN server and the inputs the domain needs from the driven
        // ports, then let the domain decide what the script must do and render it.
        return LanServer.findById(machineId, forPersistingLanServers.getAll())
            .flatMap(server -> LanServerSetupScript.forHost(server,
                forGettingPeerConfigurations.getAllPeerConfigs(),
                forResolvingServerLanCidr.resolve().orElse(null), vpnSubnet));
    }



    /**
     * Renders an operator-supplied name safe for a single log line. The lookup that precedes these
     * logs trims the request value, so a name like {@code "nas\n…"} can still reach a log statement;
     * collapsing CR/LF (and other ISO control chars) to spaces prevents forged multiline log entries.
     */
    private static String forLog(String name) {
        if (name == null) return "null";
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(c -> {
            if (Character.isISOControl(c)) sb.append(' ');
            else sb.appendCodePoint(c);
        });
        return sb.toString();
    }
}
