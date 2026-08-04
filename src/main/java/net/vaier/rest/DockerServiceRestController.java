package net.vaier.rest;

import net.vaier.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.vaier.application.CheckForImageUpdatesUseCase;
import net.vaier.application.GetLanServerScrapeUseCase;
import net.vaier.application.GetServerInfoUseCase;
import net.vaier.application.UpgradeContainerImageUseCase;
import net.vaier.domain.DockerService;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineStatus;
import net.vaier.domain.Server;
import net.vaier.domain.UpdateCheckOutcome;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/docker-services")
public class DockerServiceRestController {

    private final GetServerInfoUseCase getServerInfoUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final GetLanServerScrapeUseCase getLanServerScrapeUseCase;
    private final CheckForImageUpdatesUseCase checkForImageUpdatesUseCase;
    private final UpgradeContainerImageUseCase upgradeContainerImageUseCase;

    public DockerServiceRestController(GetServerInfoUseCase getServerInfoUseCase,
                                       DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                                       DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                                       GetLanServerScrapeUseCase getLanServerScrapeUseCase,
                                       CheckForImageUpdatesUseCase checkForImageUpdatesUseCase,
                                       UpgradeContainerImageUseCase upgradeContainerImageUseCase) {
        this.getServerInfoUseCase = getServerInfoUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getLanServerScrapeUseCase = getLanServerScrapeUseCase;
        this.checkForImageUpdatesUseCase = checkForImageUpdatesUseCase;
        this.upgradeContainerImageUseCase = upgradeContainerImageUseCase;
    }

    @GetMapping
    public ResponseEntity<List<DockerService>> getDockerServices(
        @RequestParam String address,
        @RequestParam(required = false) Integer port,
        @RequestParam(defaultValue = "false") boolean tlsEnabled
    ) {
        Server server = new Server(address, port, tlsEnabled);
        return ResponseEntity.ok(getServerInfoUseCase.getServicesWithExposedPorts(server));
    }

    @GetMapping("/vaier-server")
    public ResponseEntity<VaierServerStatusResponse> discoverVaierServerContainers() {
        // Status is the domain's: scrape succeeded → OK, scrape errored → DOWN. The browser maps
        // the enum to a CSS class and never decides what "OK" means.
        try {
            return ResponseEntity.ok(new VaierServerStatusResponse(
                MachineStatus.OK, discoverVaierServerContainersUseCase.discover()));
        } catch (Exception e) {
            return ResponseEntity.ok(new VaierServerStatusResponse(MachineStatus.DOWN, List.of()));
        }
    }

    record VaierServerStatusResponse(MachineStatus status, List<DockerService> containers) {}

    @GetMapping("/peers")
    public ResponseEntity<List<PeerContainers>> discoverPeerContainers() {
        return ResponseEntity.ok(discoverPeerContainersUseCase.discoverAll());
    }

    @GetMapping("/lan-servers")
    public ResponseEntity<List<LanServerContainers>> discoverLanServerContainers() {
        return ResponseEntity.ok(getLanServerScrapeUseCase.getLanServerContainers());
    }

    /**
     * Check the fleet's images against their registries right now, because the operator asked (#57 slice 3).
     *
     * <p>POST, not GET: it really does go and ask every registry, and that is a side effect with a rate limit
     * behind it. It remains the only mutating thing here that touches no container — Vaier still has no
     * endpoint to pull an image or restart anything, and this opens none. It acts on Vaier's own knowledge.
     *
     * <p>Authenticated like every other admin endpoint, via Traefik forward-auth. Nothing to add: it is not on
     * any anonymous path, so an unauthenticated caller cannot spend the fleet's rate limit.
     */
    @PostMapping("/image-updates/check")
    public ResponseEntity<UpdateCheckResponse> checkForImageUpdates() {
        UpdateCheckOutcome outcome = checkForImageUpdatesUseCase.checkForImageUpdates();
        return ResponseEntity.ok(new UpdateCheckResponse(
            outcome.checked(), outcome.changed(), outcome.lastCheckedAt().toString()));
    }

    /**
     * @param checked       whether the registries were really asked, or the check was coalesced by the floor
     * @param changed       whether any verdict moved
     * @param lastCheckedAt when Vaier last really looked, ISO-8601 — the honest answer for a coalesced check
     */
    record UpdateCheckResponse(boolean checked, boolean changed, String lastCheckedAt) {}

    /**
     * Upgrade one container to the image its registry now serves (#352) — the verb the update-available
     * mark never had.
     *
     * <p><b>202, not 200.</b> A pull is minutes of somebody else's bandwidth, so this accepts the request
     * and returns; the outcome arrives on the fleet SSE stream as a {@code container-upgrade-settled}
     * event carrying the sentence to show. Everything that can be refused is refused before the accept,
     * so a refusal is a status code and not an event that never comes: {@code 404} for a container that
     * machine does not have, {@code 409} for one Vaier will not recreate (carrying the plain reason),
     * {@code 424} for a machine with no stored SSH credential, {@code 400} for a malformed machine id.
     *
     * <p>Authenticated like every other endpoint here, via Traefik forward-auth — it is on no anonymous
     * path. Nothing about the container's own compose labels is trusted: the domain validates and quotes
     * them, and the run goes over SSH rather than through {@code docker-socket-proxy}, whose permissions
     * are deliberately not widened for this.
     */
    @PostMapping("/upgrade")
    public ResponseEntity<UpgradeAcceptedResponse> upgradeContainerImage(@RequestBody UpgradeRequest request) {
        MachineId machineId = MachineId.of(request.machineId());
        upgradeContainerImageUseCase.upgradeContainerImage(machineId, request.containerName());
        return ResponseEntity.accepted()
            .body(new UpgradeAcceptedResponse(machineId.value(), request.containerName()));
    }

    /**
     * @param machineId     the identity of the machine the container runs on — never its name, which two
     *                      machines may share and either may change
     * @param containerName the container as the host's Docker reports it
     */
    record UpgradeRequest(String machineId, String containerName) {}

    /** What an accepted upgrade echoes back, so the browser knows which card the settled event belongs to. */
    record UpgradeAcceptedResponse(String machineId, String containerName) {}
}
