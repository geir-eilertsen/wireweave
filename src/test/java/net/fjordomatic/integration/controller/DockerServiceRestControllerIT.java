package net.fjordomatic.integration.controller;

import net.fjordomatic.domain.ConflictException;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.NoHostCredentialException;
import net.fjordomatic.domain.NotFoundException;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.DockerService.PortMapping;
import net.fjordomatic.domain.ScopedImage;
import net.fjordomatic.domain.UpdateAvailability;
import net.fjordomatic.domain.UpdateCheckOutcome;
import net.fjordomatic.integration.base.FjordWebMvcIntegrationBase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DockerServiceRestControllerIT extends FjordWebMvcIntegrationBase {

    @Test
    void getDockerServices_returnsServicesForGivenServer() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any())).thenReturn(List.of(
                new DockerService("abc123", "app", "nginx:latest", "latest",
                        List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")),
                        List.of("bridge"), "running")
        ));

        mockMvc.perform(get("/docker-services")
                       .param("address", "10.0.0.1")
                       .param("port", "2375")
                       .param("tlsEnabled", "false"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].containerId").value("abc123"))
               .andExpect(jsonPath("$[0].containerName").value("app"))
               .andExpect(jsonPath("$[0].state").value("running"));
    }

    @Test
    void getDockerServices_passesServerParamsToUseCase() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any())).thenReturn(List.of());

        mockMvc.perform(get("/docker-services")
                       .param("address", "10.0.0.2")
                       .param("port", "2376")
                       .param("tlsEnabled", "true"))
               .andExpect(status().isOk());

        ArgumentCaptor<net.fjordomatic.domain.Server> captor = ArgumentCaptor.forClass(net.fjordomatic.domain.Server.class);
        verify(getServerInfoUseCase).getServicesWithExposedPorts(captor.capture());
        assertThat(captor.getValue().getAddress()).isEqualTo("10.0.0.2");
        assertThat(captor.getValue().getPort()).isEqualTo(2376);
        assertThat(captor.getValue().isTlsEnabled()).isTrue();
    }

    @Test
    void getDockerServices_defaultsTlsEnabledToFalse() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any())).thenReturn(List.of());

        mockMvc.perform(get("/docker-services").param("address", "10.0.0.3"))
               .andExpect(status().isOk());

        ArgumentCaptor<net.fjordomatic.domain.Server> captor = ArgumentCaptor.forClass(net.fjordomatic.domain.Server.class);
        verify(getServerInfoUseCase).getServicesWithExposedPorts(captor.capture());
        assertThat(captor.getValue().isTlsEnabled()).isFalse();
        assertThat(captor.getValue().getPort()).isNull();
    }

    @Test
    void getDockerServices_returns400WhenAddressMissing() throws Exception {
        mockMvc.perform(get("/docker-services"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void getDockerServices_returns500WhenDockerUnavailable() throws Exception {
        when(getServerInfoUseCase.getServicesWithExposedPorts(any()))
                .thenThrow(new RuntimeException("Docker host unreachable"));

        mockMvc.perform(get("/docker-services").param("address", "10.0.0.99"))
               .andExpect(status().isInternalServerError());
    }

    @Test
    void discoverFjordServerContainers_returnsFjordServerServices() throws Exception {
        when(discoverFjordServerContainersUseCase.discover()).thenReturn(List.of(
                new DockerService("c1", "vaier", "getvaier/vaier:latest", "latest",
                        List.of(new PortMapping(8080, 8888, "tcp", "0.0.0.0")),
                        List.of("vaier-net"), "running")
        ));

        mockMvc.perform(get("/docker-services/vaier-server"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("OK"))
               .andExpect(jsonPath("$.containers[0].containerId").value("c1"))
               .andExpect(jsonPath("$.containers[0].containerName").value("vaier"));
    }

    @Test
    void discoverFjordServerContainers_returnsEmptyContainersWithOkStatus() throws Exception {
        when(discoverFjordServerContainersUseCase.discover()).thenReturn(List.of());

        mockMvc.perform(get("/docker-services/vaier-server"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("OK"))
               .andExpect(jsonPath("$.containers").isEmpty());
    }

    @Test
    void discoverFjordServerContainers_returnsDownStatusWhenDockerUnavailable() throws Exception {
        when(discoverFjordServerContainersUseCase.discover())
                .thenThrow(new RuntimeException("docker.sock not accessible"));

        mockMvc.perform(get("/docker-services/vaier-server"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("DOWN"))
               .andExpect(jsonPath("$.containers").isEmpty());
    }

    @Test
    void discoverPeerContainers_returnsPeerContainerList() throws Exception {
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
                new PeerContainers(TestMachineIds.of("peer1").value(), "peer1", "10.13.13.2", "OK", List.of(
                        new DockerService("c1", "svc", "img:1.0", "1.0",
                                List.of(), List.of(), "running")
                ), false, "lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110")
        ));

        mockMvc.perform(get("/docker-services/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].peerId").value("peer1"))
               .andExpect(jsonPath("$[0].vpnIp").value("10.13.13.2"))
               .andExpect(jsonPath("$[0].status").value("OK"))
               .andExpect(jsonPath("$[0].containers[0].containerName").value("svc"))
               .andExpect(jsonPath("$[0].wireguardOutdated").value(false));
    }

    @Test
    void discoverPeerContainers_returnsEmptyListWhenNoPeers() throws Exception {
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of());

        mockMvc.perform(get("/docker-services/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void discoverPeerContainers_returnsUnreachablePeerEntry() throws Exception {
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(
                new PeerContainers(TestMachineIds.of("peer2").value(), "peer2", "10.13.13.3", "UNREACHABLE",
                        List.of(), false, "lscr.io/linuxserver/wireguard:1.0.20250521-r1-ls110")
        ));

        mockMvc.perform(get("/docker-services/peers"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$[0].peerId").value("peer2"))
               .andExpect(jsonPath("$[0].status").value("UNREACHABLE"))
               .andExpect(jsonPath("$[0].containers").isEmpty());
    }

    // --- #57 slice 3: the operator's own update check ---

    @Test
    void checkForImageUpdates_reportsWhatFjordActuallyDid() throws Exception {
        when(checkForImageUpdatesUseCase.checkForImageUpdates())
            .thenReturn(UpdateCheckOutcome.checked(
                Map.of(new ScopedImage("Fjord server", "vaultwarden/server:latest"),
                    UpdateAvailability.UPDATE_AVAILABLE),
                Map.of(new ScopedImage("Fjord server", "vaultwarden/server:latest"),
                    UpdateAvailability.UP_TO_DATE),
                Instant.parse("2026-07-17T12:00:00Z")));

        mockMvc.perform(post("/docker-services/image-updates/check"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.checked").value(true))
               .andExpect(jsonPath("$.changed").value(true))
               .andExpect(jsonPath("$.lastCheckedAt").value("2026-07-17T12:00:00Z"));
    }

    @Test
    void checkForImageUpdates_tellsTheBrowserWhenItDidNotActuallyCheck() throws Exception {
        // The rate-limit floor's honesty rule, all the way out to the wire. A coalesced check must not be
        // dressed up as a real one — the browser needs the difference to say something true.
        when(checkForImageUpdatesUseCase.checkForImageUpdates())
            .thenReturn(UpdateCheckOutcome.coalesced(Instant.parse("2026-07-17T11:59:30Z")));

        mockMvc.perform(post("/docker-services/image-updates/check"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.checked").value(false))
               .andExpect(jsonPath("$.changed").value(false))
               .andExpect(jsonPath("$.lastCheckedAt").value("2026-07-17T11:59:30Z"));
    }

    @Test
    void checkForImageUpdates_isNotAGet_becauseItReallyGoesAndAsksEveryRegistry() throws Exception {
        mockMvc.perform(get("/docker-services/image-updates/check"))
               .andExpect(status().isMethodNotAllowed());
    }

    // --- Updating a container's image (#352) ---

    private static final String MACHINE_ID = TestMachineIds.of("apalveien5").value();

    private static String updateBody(String machineId, String containerName) {
        return "{\"machineId\":\"" + machineId + "\",\"containerName\":\"" + containerName + "\"}";
    }

    @Test
    void update_isAccepted_andSaysSoRatherThanWaitingForThePull() throws Exception {
        mockMvc.perform(post("/docker-services/update")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(updateBody(MACHINE_ID, "vaultwarden")))
               .andExpect(status().isAccepted())
               .andExpect(jsonPath("$.machineId").value(MACHINE_ID))
               .andExpect(jsonPath("$.containerName").value("vaultwarden"));

        verify(updateContainerImageUseCase)
            .updateContainerImage(new MachineId(MACHINE_ID), "vaultwarden");
    }

    @Test
    void update_ofAContainerFjordWillNotRecreate_is409WithTheReason() throws Exception {
        doThrow(new ConflictException("Fjord holds no compose coordinates for pihole, so it does not know"
            + " how it was started and will not recreate it."))
            .when(updateContainerImageUseCase).updateContainerImage(any(), any());

        mockMvc.perform(post("/docker-services/update")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(updateBody(MACHINE_ID, "pihole")))
               .andExpect(status().isConflict())
               .andExpect(jsonPath("$.message").value(containsString("how it was started")));
    }

    @Test
    void update_ofAnUnknownContainer_is404() throws Exception {
        doThrow(new NotFoundException("No container named ghost on that machine"))
            .when(updateContainerImageUseCase).updateContainerImage(any(), any());

        mockMvc.perform(post("/docker-services/update")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(updateBody(MACHINE_ID, "ghost")))
               .andExpect(status().isNotFound());
    }

    @Test
    void update_ofAMachineWithNoCredential_is424_soTheOperatorKnowsWhatToAdd() throws Exception {
        doThrow(new NoHostCredentialException("apalveien5"))
            .when(updateContainerImageUseCase).updateContainerImage(any(), any());

        mockMvc.perform(post("/docker-services/update")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(updateBody(MACHINE_ID, "vaultwarden")))
               .andExpect(status().isFailedDependency());
    }

    @Test
    void update_withAMalformedMachineId_is400_andNeverReachesTheUseCase() throws Exception {
        mockMvc.perform(post("/docker-services/update")
                   .contentType(MediaType.APPLICATION_JSON)
                   .content(updateBody("not-a-machine-id", "vaultwarden")))
               .andExpect(status().isBadRequest());

        verify(updateContainerImageUseCase, never()).updateContainerImage(any(), any());
    }

    @Test
    void update_isNotAGet_becauseItChangesWhatRunsOnAHost() throws Exception {
        mockMvc.perform(get("/docker-services/update"))
               .andExpect(status().isMethodNotAllowed());
    }
}
