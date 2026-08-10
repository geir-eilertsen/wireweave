package net.fjordomatic.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.Server;
import net.fjordomatic.domain.ContainerUpdateEligibility;
import net.fjordomatic.domain.port.ForDiscoveringLanServerContainers;
import net.fjordomatic.domain.port.ForGettingLanServers;
import net.fjordomatic.domain.port.ForGettingLanServers.LanServerView;
import net.fjordomatic.domain.port.ForCheckingDockerCommandAccess;
import net.fjordomatic.domain.port.ForGettingServerInfo;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driven adapter that scrapes containers off a registered LAN server's Docker socket, hopping through
 * the relay peer's tunnel (or straight from the Fjord container when the address is in the server's own
 * subnet). This is genuinely infrastructure — a live Docker/SSH scrape — that used to sit on
 * {@code ContainerService}; a {@code *Service} must not implement a driven ({@code For*}) port, so it
 * moved here. Reads the LAN-server catalogue via {@link ForGettingLanServers} and the Docker socket via
 * {@link ForGettingServerInfo}.
 */
@Component
@Slf4j
public class LanServerContainerDiscoveryAdapter implements ForDiscoveringLanServerContainers {

    private final ForGettingLanServers forGettingLanServers;
    private final ForGettingServerInfo forGettingServerInfo;
    // What Fjord last saw of each machine's Docker access, so a container's verdict can say that this
    // machine's Docker is out of reach rather than offering an update that cannot run.
    private final ForCheckingDockerCommandAccess dockerAccess;

    public LanServerContainerDiscoveryAdapter(ForGettingLanServers forGettingLanServers,
                                              ForGettingServerInfo forGettingServerInfo,
                                              ForCheckingDockerCommandAccess dockerAccess) {
        this.forGettingLanServers = forGettingLanServers;
        this.forGettingServerInfo = forGettingServerInfo;
        this.dockerAccess = dockerAccess;
    }

    @Override
    public List<LanServerContainers> discoverAllLanServerContainers() {
        return forGettingLanServers.getAll().stream()
            .filter(view -> view.server().runsDocker())
            .map(this::scrapeLanServer)
            .toList();
    }

    @Override
    public LanServerContainers discoverLanServerContainersForHost(String name) {
        LanServerView view = forGettingLanServers.getAll().stream()
            .filter(v -> v.server().name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("LAN server not found: " + name));
        if (!view.server().runsDocker()) {
            throw new IllegalArgumentException(
                "LAN server " + name + " does not run Docker");
        }
        return scrapeLanServer(view);
    }

    private LanServerContainers scrapeLanServer(LanServerView view) {
        var server = view.server();
        if (view.relayPeerName() == null) {
            log.debug("Skipping LAN server {} ({}) — not inside any relay peer's lanCidr nor the server LAN CIDR",
                server.name(), server.lanAddress());
            return new LanServerContainers(server.machineId().value(), server.name(), server.lanAddress(), server.dockerPort(),
                null, "UNREACHABLE", List.of());
        }
        // relayPeerName is either a relay peer (scrape hops through its tunnel + LAN forwarding)
        // or LanAnchor.FJORD_SERVER_NAME (scrape goes straight from the Fjord container, since the
        // address is in the Fjord server's own subnet). The Docker socket target is the same.
        try {
            Server target = new Server(server.lanAddress(), server.dockerPort(), false);
            // A LAN server is the operator's machine: its containers are theirs to update, whatever they
            // are named. The verdict is the domain's — this adapter only says which machine was scraped.
            List<DockerService> containers = ContainerUpdateEligibility.judgeOperatorContainers(
                forGettingServerInfo.getServicesWithExposedPorts(target),
                dockerAccess.accessFor(server.machineId()));
            log.info("Discovered {} containers on LAN server {} ({}) via {}",
                containers.size(), server.name(), server.lanAddress(), view.relayPeerName());
            return new LanServerContainers(server.machineId().value(), server.name(), server.lanAddress(), server.dockerPort(),
                view.relayPeerName(), "OK", containers);
        } catch (Exception e) {
            log.warn("Failed to query Docker on LAN server {} ({}): {}",
                server.name(), server.lanAddress(), e.getMessage());
            return new LanServerContainers(server.machineId().value(), server.name(), server.lanAddress(), server.dockerPort(),
                view.relayPeerName(), "UNREACHABLE", List.of());
        }
    }
}
