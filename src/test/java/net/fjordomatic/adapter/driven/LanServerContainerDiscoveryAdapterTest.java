package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.ComposeCoordinates;
import net.fjordomatic.domain.DockerCommandAccess;
import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.LanServer;
import net.fjordomatic.domain.DockerService.PortMapping;
import net.fjordomatic.domain.UpdateAvailability;
import net.fjordomatic.domain.ContainerUpdateEligibility;
import net.fjordomatic.domain.port.ForDiscoveringLanServerContainers.LanServerContainers;
import net.fjordomatic.domain.port.ForGettingLanServers;
import net.fjordomatic.domain.port.ForGettingLanServers.LanServerView;
import net.fjordomatic.domain.port.ForCheckingDockerCommandAccess;
import net.fjordomatic.domain.port.ForGettingServerInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanServerContainerDiscoveryAdapterTest {

    @Mock ForGettingLanServers forGettingLanServers;
    @Mock ForGettingServerInfo forGettingServerInfo;
    @Mock ForCheckingDockerCommandAccess dockerAccess;

    @InjectMocks LanServerContainerDiscoveryAdapter adapter;

    private static LanServerView dockerHost(String name, String relay) {
        return new LanServerView(new LanServer(name, "192.168.3.50", true, 2375), relay);
    }

    private static DockerService container(String name) {
        return new DockerService("id-" + name, name, "img:latest", "v",
            List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")), List.of(), "running",
            "sha256:x", UpdateAvailability.UNKNOWN);
    }

    @Test
    void discoverAll_skipsNonDockerHostsAndScrapesTheRest() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5"),
            dockerHost("nas", "apalveien5")));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of(container("app")));

        assertThat(adapter.discoverAllLanServerContainers())
            .extracting(LanServerContainers::name, LanServerContainers::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("nas", "OK"));
    }

    @Test
    void scrape_withoutARelayAnchor_reportsUnreachableWithoutScraping() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", null)));

        assertThat(adapter.discoverAllLanServerContainers())
            .extracting(LanServerContainers::name, LanServerContainers::status)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("nas", "UNREACHABLE"));
    }

    @Test
    void scrape_whenDockerQueryThrows_reportsUnreachable() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenThrow(new RuntimeException("connection refused"));

        assertThat(adapter.discoverAllLanServerContainers())
            .extracting(LanServerContainers::status)
            .containsExactly("UNREACHABLE");
    }

    @Test
    void discoverForHost_unknownName_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));

        assertThatThrownBy(() -> adapter.discoverLanServerContainersForHost("ghost"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoverForHost_runsDockerFalse_throws() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(
            new LanServerView(new LanServer("printer", "192.168.3.20", false, null), "apalveien5")));

        assertThatThrownBy(() -> adapter.discoverLanServerContainersForHost("printer"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void discoverForHost_docker_returnsScrape() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(forGettingServerInfo.getServicesWithExposedPorts(any())).thenReturn(List.of(container("app")));

        assertThat(adapter.discoverLanServerContainersForHost("nas").status()).isEqualTo("OK");
    }

    @Test
    void scrape_judgesALanServersContainersAsTheOperatorsOwn() {
        // A LAN server is the operator's machine: nothing on it is Fjord's own stack, whatever it is
        // named, and the Explorer must be handed the verdict rather than work it out itself.
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(dockerAccess.accessFor(any())).thenReturn(DockerCommandAccess.GRANTED);
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(composeManaged("traefik"), container("hand-started")));

        assertThat(adapter.discoverAllLanServerContainers().get(0).containers())
            .extracting(DockerService::containerName, DockerService::updateEligibility)
            .containsExactly(
                tuple("traefik", ContainerUpdateEligibility.UPDATABLE),
                tuple("hand-started", ContainerUpdateEligibility.NOT_COMPOSE_MANAGED));
    }

    @Test
    void scrape_withholdsTheUpdateOnALanServerWhoseDockerFjordCannotDrive() {
        // The scrape itself goes over the Docker API and works perfectly — which is exactly why the
        // verdict has to carry the machine's SSH-side Docker access, learned elsewhere and handed in.
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(dockerAccess.accessFor(any())).thenReturn(DockerCommandAccess.REFUSED);
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(composeManaged("plex")));

        assertThat(adapter.discoverAllLanServerContainers().get(0).containers())
            .extracting(DockerService::containerName, DockerService::updateEligibility)
            .containsExactly(tuple("plex", ContainerUpdateEligibility.NO_DOCKER_ACCESS));
    }

    @Test
    void scrape_ofAMachineNobodyHasSweptYet_stillOffersTheUpdate() {
        when(forGettingLanServers.getAll()).thenReturn(List.of(dockerHost("nas", "apalveien5")));
        when(dockerAccess.accessFor(any())).thenReturn(DockerCommandAccess.UNKNOWN);
        when(forGettingServerInfo.getServicesWithExposedPorts(any()))
            .thenReturn(List.of(composeManaged("plex")));

        assertThat(adapter.discoverAllLanServerContainers().get(0).containers())
            .extracting(DockerService::updateEligibility)
            .containsExactly(ContainerUpdateEligibility.UPDATABLE);
    }

    /** A compose-managed container, as a scrape of a real LAN server reports one. */
    private static DockerService composeManaged(String name) {
        return DockerService.builder()
            .containerId("id-" + name)
            .containerName(name)
            .image("img:latest")
            .version("v")
            .ports(List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")))
            .networks(List.of())
            .state("running")
            .imageDigest("sha256:x")
            .updateAvailable(UpdateAvailability.UNKNOWN)
            .composeCoordinates(ComposeCoordinates.fromLabels(Map.of(
                "com.docker.compose.project", name,
                "com.docker.compose.service", name,
                "com.docker.compose.project.config_files", "/srv/" + name + "/docker-compose.yml",
                "com.docker.compose.project.working_dir", "/srv/" + name)).orElseThrow())
            .build();
    }
}
