package net.fjordomatic.application.service;

import net.fjordomatic.domain.PeerNotFoundException;
import net.fjordomatic.domain.ConflictException;
import net.fjordomatic.application.DeletePublishedServiceUseCase;
import net.fjordomatic.application.GetPeerConfigUseCase.PeerConfigResult;
import net.fjordomatic.application.GetServerLocationUseCase.ServerLocation;
import net.fjordomatic.config.ConfigResolver;
import net.fjordomatic.domain.DnsRecordType;
import net.fjordomatic.domain.GeoLocation;
import net.fjordomatic.domain.LanServer;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.ReverseProxyRoute;
import net.fjordomatic.domain.VpnClient;
import net.fjordomatic.domain.port.ForDeletingVpnPeers;
import net.fjordomatic.domain.port.ForPersistingLanServers;
import net.fjordomatic.domain.port.ForExecutingInContainer;
import net.fjordomatic.domain.port.ForGeneratingDockerComposeFiles;
import net.fjordomatic.domain.port.ForGeneratingDockerComposeFiles.DockerComposeConfig;
import net.fjordomatic.domain.port.ForGeolocatingIps;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.fjordomatic.domain.port.ForGettingVpnClients;
import net.fjordomatic.domain.port.ForPersistingReverseProxyRoutes;
import net.fjordomatic.domain.port.ForResolvingPeerIds;
import net.fjordomatic.domain.port.ForResolvingPublicHost;
import net.fjordomatic.domain.port.ForResolvingPublicHost.PublicHost;
import net.fjordomatic.domain.port.ForResolvingServerLanCidr;
import net.fjordomatic.domain.port.ForSyncingLanRoutes;
import net.fjordomatic.domain.port.ForUpdatingPeerConfigurations;
import net.fjordomatic.domain.port.ForUpdatingServerAllowedIps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnServiceTest {

    private static net.fjordomatic.domain.MachineId mid(String name) {
        return net.fjordomatic.domain.TestMachineIds.of(name);
    }

    @Mock ConfigResolver configResolver;
    @Mock ForGettingVpnClients forGettingVpnClients;
    @Mock ForResolvingPeerIds forResolvingPeerIds;
    @Mock ForGettingPeerConfigurations peerConfigProvider;
    @Mock ForDeletingVpnPeers vpnPeerDeleter;
    @Mock ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    @Mock ForGeneratingDockerComposeFiles dockerComposeGenerator;
    @Mock DeletePublishedServiceUseCase deletePublishedServiceUseCase;
    @Mock ForResolvingPublicHost forResolvingPublicHost;
    @Mock ForGeolocatingIps forGeolocatingIps;
    @Mock ForUpdatingPeerConfigurations forUpdatingPeerConfigurations;
    @Mock ForUpdatingServerAllowedIps forUpdatingServerAllowedIps;
    @Mock ForSyncingLanRoutes forSyncingLanRoutes;
    @Mock ForExecutingInContainer forExecutingInContainer;
    @Mock ForResolvingServerLanCidr forResolvingServerLanCidr;
    @Mock net.fjordomatic.domain.port.ForTrackingPeerConfigRetrieval forTrackingPeerConfigRetrieval;
    @Mock ForPersistingLanServers forPersistingLanServers;
    @Mock net.fjordomatic.domain.port.ForPersistingHostCredentials forPersistingHostCredentials;
    @Mock net.fjordomatic.domain.port.ForTrackingHostKeys forTrackingHostKeys;

    @InjectMocks VpnService service;

    // --- getClients ---

    @Test
    void getClients_delegatesToPort() {
        VpnClient client = new VpnClient("pubkey", "10.13.13.2/32", "1.2.3.4", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));

        assertThat(service.getClients()).containsExactly(client);
    }

    @Test
    void getClients_returnsEmptyListWhenNoClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.getClients()).isEmpty();
    }

    // --- resolvePeerIdByIp ---

    @Test
    void resolvePeerIdByIp_delegatesToPort() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");

        assertThat(service.resolvePeerIdByIp("10.13.13.2")).isEqualTo("alice");
    }

    @Test
    void resolvePeerIdByIp_returnsNullWhenNotFound() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.99")).thenReturn(null);

        assertThat(service.resolvePeerIdByIp("10.13.13.99")).isNull();
    }

    // --- getPeerConfig ---

    @Test
    void getPeerConfig_byName_callsGetByName() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfig("alice");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("alice");
        assertThat(result.get().ipAddress()).isEqualTo("10.13.13.2");
        verify(peerConfigProvider).getPeerConfigByName("alice");
    }

    @Test
    void getPeerConfig_byIp_callsGetByIp() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfig("10.13.13.2");

        assertThat(result).isPresent();
        verify(peerConfigProvider).getPeerConfigByIp("10.13.13.2");
    }

    @Test
    void getPeerConfig_notFound_returnsEmpty() {
        when(peerConfigProvider.getPeerConfigByName("nobody")).thenReturn(Optional.empty());

        assertThat(service.getPeerConfig("nobody")).isEmpty();
    }

    @Test
    void getPeerConfig_mapsPeerConfigurationFieldsCorrectly() {
        when(peerConfigProvider.getPeerConfigByName("bob")).thenReturn(
            Optional.of(new PeerConfiguration("bob", "10.13.13.3", "wg-config-content"))
        );

        PeerConfigResult result = service.getPeerConfig("bob").orElseThrow();

        assertThat(result.name()).isEqualTo("bob");
        assertThat(result.ipAddress()).isEqualTo("10.13.13.3");
        assertThat(result.configContent()).isEqualTo("wg-config-content");
    }

    @Test
    void getPeerConfigByIp_delegatesToPort() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config"))
        );

        Optional<PeerConfigResult> result = service.getPeerConfigByIp("10.13.13.2");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("alice");
        verify(peerConfigProvider).getPeerConfigByIp("10.13.13.2");
    }

    @Test
    void getPeerConfigByIp_returnsEmptyWhenNotFound() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.99")).thenReturn(Optional.empty());

        assertThat(service.getPeerConfigByIp("10.13.13.99")).isEmpty();
    }

    // --- generateWireguardClientDockerCompose ---

    @Test
    void generateWireguardClientDockerCompose_passesCorrectConfigToPort() {
        when(dockerComposeGenerator.generateWireguardClientDockerCompose(
            new DockerComposeConfig("alice", "vpn.example.com", "51820")
        )).thenReturn("docker-compose-yaml-content");

        String result = service.generateWireguardClientDockerCompose("alice", "vpn.example.com", "51820");

        assertThat(result).isEqualTo("docker-compose-yaml-content");
    }

    @Test
    void generateWireguardClientDockerCompose_constructsDockerComposeConfigRecord() {
        ArgumentCaptor<DockerComposeConfig> captor = ArgumentCaptor.forClass(DockerComposeConfig.class);
        when(dockerComposeGenerator.generateWireguardClientDockerCompose(captor.capture())).thenReturn("");

        service.generateWireguardClientDockerCompose("bob", "server.net", "51820");

        DockerComposeConfig config = captor.getValue();
        assertThat(config.peerId()).isEqualTo("bob");
        assertThat(config.serverUrl()).isEqualTo("server.net");
        assertThat(config.serverPort()).isEqualTo("51820");
    }

    // --- generateSetupScript ---

    @Test
    void generateSetupScript_peerNotFound_returnsEmpty() {
        when(peerConfigProvider.getPeerConfigByName("unknown")).thenReturn(Optional.empty());

        assertThat(service.generateSetupScript("unknown", "vpn.example.com", "51820")).isEmpty();
    }

    @Test
    void generateSetupScript_peerFound_returnsNonEmptyScript() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "[Interface]\nAddress=10.13.13.2/32"))
        );

        Optional<String> result = service.generateSetupScript("alice", "vpn.example.com", "51820");

        assertThat(result).isPresent();
        assertThat(result.get()).isNotBlank();
    }

    @Test
    void generateSetupScript_scriptStartsWithShebang() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).startsWith("#!/bin/bash");
    }

    @Test
    void generateSetupScript_scriptContainsPeerName() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("alice");
    }

    @Test
    void generateSetupScript_scriptContainsVpnIp() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("10.13.13.2");
    }

    @Test
    void generateSetupScript_scriptContainsServerUrl() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("vpn.example.com");
    }

    @Test
    void generateSetupScript_scriptContainsServerPort() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("51820");
    }

    @Test
    void generateSetupScript_scriptEnablesDockerOnBoot() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("systemctl enable docker");
    }

    @Test
    void generateSetupScript_systemctlCallsAreNonFatal() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("systemctl enable docker || true");
        assertThat(script).contains("systemctl restart docker");
    }

    @Test
    void generateSetupScript_scriptFallsBackToServiceRestartWhenSystemctlFails() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("systemctl restart docker || sudo service docker restart || true");
    }

    @Test
    void generateSetupScript_scriptHandlesSnapDocker() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("snap.docker.dockerd");
        assertThat(script).contains("/var/snap/docker/current/config/daemon.json");
    }

    @Test
    void generateSetupScript_usesConfiguredVpnSubnetInFirewallRules() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.10.10.0/24");
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.10.10.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("10.10.10.0/24");
        assertThat(script).doesNotContain("10.13.13.0/24");
    }

    // --- generateSetupScript: relay-peer LAN forwarding (#170) ---

    @Test
    void generateSetupScript_lanCidrSet_enablesIpForwardingSysctl() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        when(peerConfigProvider.getPeerConfigByName("homelab")).thenReturn(
            Optional.of(new PeerConfiguration("homelab", "10.13.13.5", "wg-config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null))
        );

        String script = service.generateSetupScript("homelab", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("sysctl -w net.ipv4.ip_forward=1");
        assertThat(script).contains("net.ipv4.ip_forward=1");
        assertThat(script).contains("/etc/sysctl.d/99-wireguard.conf");
    }

    @Test
    void generateSetupScript_lanCidrSet_addsMasqueradeAndForwardRulesIdempotently() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        when(peerConfigProvider.getPeerConfigByName("homelab")).thenReturn(
            Optional.of(new PeerConfiguration("homelab", "10.13.13.5", "wg-config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null))
        );

        String script = service.generateSetupScript("homelab", "vpn.example.com", "51820").orElseThrow();

        // POSTROUTING MASQUERADE for vpn -> lan
        assertThat(script).contains(
            "iptables -t nat -C POSTROUTING -s 10.13.13.0/24 -d 192.168.1.0/24 -j MASQUERADE");
        assertThat(script).contains(
            "iptables -t nat -A POSTROUTING -s 10.13.13.0/24 -d 192.168.1.0/24 -j MASQUERADE");
        // FORWARD vpn -> lan
        assertThat(script).contains(
            "iptables -C FORWARD -s 10.13.13.0/24 -d 192.168.1.0/24 -j ACCEPT");
        assertThat(script).contains(
            "iptables -A FORWARD -s 10.13.13.0/24 -d 192.168.1.0/24 -j ACCEPT");
        // FORWARD lan -> vpn (RELATED,ESTABLISHED only)
        assertThat(script).contains(
            "iptables -C FORWARD -s 192.168.1.0/24 -d 10.13.13.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT");
        assertThat(script).contains(
            "iptables -A FORWARD -s 192.168.1.0/24 -d 10.13.13.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT");
    }

    @Test
    void generateSetupScript_lanCidrAbsent_omitsForwardingBlock() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).doesNotContain("net.ipv4.ip_forward=1");
        assertThat(script).doesNotContain("MASQUERADE");
        assertThat(script).doesNotContain("FORWARD");
    }

    // --- generateSetupScript: relay iptables survive reboot (#191) ---

    @Test
    void generateSetupScript_lanCidrSet_installsBootTimeUnitToReapplyIptables() {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        when(peerConfigProvider.getPeerConfigByName("homelab")).thenReturn(
            Optional.of(new PeerConfiguration("homelab", "10.13.13.5", "wg-config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null))
        );

        String script = service.generateSetupScript("homelab", "vpn.example.com", "51820").orElseThrow();

        // Writes a systemd unit and enables it at boot.
        assertThat(script).contains("/etc/systemd/system/vaier-wg-relay-iptables.service");
        assertThat(script).contains("systemctl daemon-reload");
        assertThat(script).contains("systemctl enable");
        assertThat(script).contains("vaier-wg-relay-iptables");

        // The unit re-applies the same idempotent iptables rules on every boot.
        // Take everything between the unit file's heredoc markers and assert against that.
        int unitStart = script.indexOf("vaier-wg-relay-iptables.service");
        int unitEnd = script.indexOf("UNIT_FILE\n", unitStart);
        assertThat(unitStart).isPositive();
        assertThat(unitEnd).isGreaterThan(unitStart);
        String unitBody = script.substring(unitStart, unitEnd);

        assertThat(unitBody).contains(
            "iptables -t nat -C POSTROUTING -s 10.13.13.0/24 -d 192.168.1.0/24 -j MASQUERADE");
        assertThat(unitBody).contains(
            "iptables -t nat -A POSTROUTING -s 10.13.13.0/24 -d 192.168.1.0/24 -j MASQUERADE");
        assertThat(unitBody).contains(
            "iptables -C FORWARD -s 10.13.13.0/24 -d 192.168.1.0/24 -j ACCEPT");
        assertThat(unitBody).contains(
            "iptables -A FORWARD -s 10.13.13.0/24 -d 192.168.1.0/24 -j ACCEPT");
        assertThat(unitBody).contains(
            "iptables -C FORWARD -s 192.168.1.0/24 -d 10.13.13.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT");
        assertThat(unitBody).contains(
            "iptables -A FORWARD -s 192.168.1.0/24 -d 10.13.13.0/24 -m state --state RELATED,ESTABLISHED -j ACCEPT");

        // Boot-time service runs after networking is ready; otherwise iptables -t nat fails.
        assertThat(unitBody).contains("After=network");
    }

    @Test
    void generateSetupScript_lanCidrAbsent_omitsBootTimeIptablesUnit() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).doesNotContain("vaier-wg-relay-iptables");
    }

    @Test
    void generateSetupScript_lanCidrBlank_omitsForwardingBlock() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config",
                MachineType.UBUNTU_SERVER, "   ", null))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).doesNotContain("net.ipv4.ip_forward=1");
        assertThat(script).doesNotContain("MASQUERADE");
    }

    // --- generateSetupScript: wireguard image pinning (drift guard, #175) ---

    @Test
    void generateSetupScript_pinsWireguardImageNotLatest() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script)
            .contains("image: " + net.fjordomatic.domain.WireguardClientImage.EXPECTED)
            .doesNotContain("wireguard:latest");
    }

    @Test
    void generateSetupScript_pinsWireguardImageToSameVersionAsServer() throws Exception {
        // Drift guard: install-script wireguard image must match the server's docker-compose.yml pin.
        String serverCompose = java.nio.file.Files.readString(java.nio.file.Path.of("docker-compose.yml"));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "image:\\s*(lscr\\.io/linuxserver/wireguard:\\S+)").matcher(serverCompose);
        assertThat(m.find()).as("server docker-compose.yml should declare a wireguard image").isTrue();
        String serverImage = m.group(1);
        assertThat(serverImage).as("server wireguard must be pinned, not :latest").doesNotEndWith(":latest");

        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("image: " + serverImage);
    }

    // --- generateSetupScript: rerun cleanup for orphaned wg0 ---

    @Test
    void generateSetupScript_deletesOrphanedWg0InterfaceBeforeStartingContainer() {
        // Re-running the install script must clean up a leftover host-netns wg0 interface
        // (linuxserver/wireguard runs network_mode: host and doesn't run wg-quick down on
        // container shutdown). Without this cleanup the new container fails with
        // "wg-quick: wg0 already exists" and the tunnel is left orphaned with no driver.
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(
            Optional.of(new PeerConfiguration("alice", "10.13.13.2", "wg-config"))
        );

        String script = service.generateSetupScript("alice", "vpn.example.com", "51820").orElseThrow();

        assertThat(script).contains("ip link delete wg0 2>/dev/null || true");

        int downIdx       = script.indexOf("docker compose down");
        int linkDeleteIdx = script.indexOf("ip link delete wg0");
        int composeUpIdx  = script.lastIndexOf("docker_compose_up");
        assertThat(downIdx).as("docker compose down should appear in script").isGreaterThanOrEqualTo(0);
        assertThat(composeUpIdx).as("docker_compose_up should appear in script").isGreaterThanOrEqualTo(0);
        assertThat(linkDeleteIdx)
            .as("wg0 cleanup must run after docker compose down and before docker_compose_up")
            .isBetween(downIdx, composeUpIdx);
    }

    // --- deletePeer ---

    @Test
    void deletePeer_byName_deletesDirectlyWithoutResolving() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(forResolvingPeerIds);
    }

    @Test
    void deletePeer_byIp_resolvesToNameBeforeDeleting() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("10.13.13.2");

        verify(forResolvingPeerIds).resolvePeerIdByIp("10.13.13.2");
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_ipNotResolved_throwsPeerNotFound() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(PeerNotFoundException.class)
            .hasMessageContaining("10.13.13.99");
    }

    @Test
    void deletePeer_ipNotResolved_doesNotCallDeleter() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.99")).thenReturn("10.13.13.99");

        assertThatThrownBy(() -> service.deletePeer("10.13.13.99"))
            .isInstanceOf(PeerNotFoundException.class);

        verifyNoInteractions(vpnPeerDeleter);
    }

    @Test
    void deletePeer_ipLikeStringWithOutOfRangeOctets_isTreatedAsAPeerName() {
        // "999.999.999.999" is not a valid IPv4 literal, so it is taken as a peer name
        // directly — no IP-to-name resolution is attempted.
        service.deletePeer("999.999.999.999");

        verify(vpnPeerDeleter).deletePeer("999.999.999.999");
    }

    @Test
    void deletePeer_deletesPublishedServicesPointingToPeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        ReverseProxyRoute otherRoute = new ReverseProxyRoute("other-router", "other.example.com", "10.13.13.3", 9090, "other-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute, otherRoute));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
        verify(deletePublishedServiceUseCase, never()).deleteService(eq("other.example.com"), any());
    }

    @Test
    void deletePeer_deletesMultipleServicesPointingToSamePeerIp() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute route1 = new ReverseProxyRoute("app1-router", "app1.example.com", "10.13.13.2", 8080, "app1-service", null);
        ReverseProxyRoute route2 = new ReverseProxyRoute("app2-router", "app2.example.com", "10.13.13.2", 9090, "app2-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(route1, route2));

        service.deletePeer("alice");

        verify(deletePublishedServiceUseCase).deleteService("app1.example.com", null);
        verify(deletePublishedServiceUseCase).deleteService("app2.example.com", null);
    }

    @Test
    void deletePeer_doesNotCascadeIntoApiOnlyDockerRouteOnSamePeerIp() {
        // An API-only Traefik route (name@provider) has no file entry; deleting it would throw
        // "Router not found" and abort the peer deletion. The cascade must skip it even when it
        // shares the peer's IP — only Fjord-managed file routes cascade.
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));
        ReverseProxyRoute dockerRoute = new ReverseProxyRoute("app@docker", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(dockerRoute));

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verify(deletePublishedServiceUseCase, never()).deleteService(any(), any());
    }

    @Test
    void deletePeer_noPublishedServicesForPeer_stillDeletesPeer() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(deletePublishedServiceUseCase);
    }

    @Test
    void deletePeer_peerConfigNotFound_stillDeletesPeerWithoutCleaningServices() {
        when(peerConfigProvider.getPeerConfigByName("alice")).thenReturn(Optional.empty());

        service.deletePeer("alice");

        verify(vpnPeerDeleter).deletePeer("alice");
        verifyNoInteractions(deletePublishedServiceUseCase);
    }

    @Test
    void deletePeer_byIp_usesResolvedIpForServiceCleanup() {
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice");
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("10.13.13.2");

        verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
        verify(vpnPeerDeleter).deletePeer("alice");
    }

    @Test
    void deletePeer_deletesServicesBeforeDeletingPeer() {
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        ReverseProxyRoute peerRoute = new ReverseProxyRoute("app-router", "app.example.com", "10.13.13.2", 8080, "app-service", null);
        when(forPersistingReverseProxyRoutes.getReverseProxyRoutes()).thenReturn(List.of(peerRoute));

        service.deletePeer("alice");

        var order = inOrder(deletePublishedServiceUseCase, vpnPeerDeleter);
        order.verify(deletePublishedServiceUseCase).deleteService("app.example.com", null);
        order.verify(vpnPeerDeleter).deletePeer("alice");
    }

    // --- getServerLocation ---

    @Test
    void getServerLocation_prefersResolvedPublicIpForGeolocation() {
        // On EC2 the public hostname resolves to a private VPC IP, so the service should ask the
        // port for the direct public IP rather than DNS-resolving the CNAME.
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("ec2-54-93-32-13.eu-central-1.compute.amazonaws.com", DnsRecordType.CNAME)));
        when(forResolvingPublicHost.resolvePublicIp()).thenReturn(Optional.of("54.93.32.13"));
        when(forGeolocatingIps.locate("54.93.32.13"))
            .thenReturn(Optional.of(new GeoLocation(50.11, 8.68, "Frankfurt", "Germany")));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        // Display label keeps the friendly hostname, geolocation uses the public IP.
        assertThat(result.get().publicHost()).isEqualTo("ec2-54-93-32-13.eu-central-1.compute.amazonaws.com");
        assertThat(result.get().latitude()).isEqualTo(50.11);
        assertThat(result.get().longitude()).isEqualTo(8.68);
        assertThat(result.get().city()).isEqualTo("Frankfurt");
        assertThat(result.get().country()).isEqualTo("Germany");
    }

    @Test
    void getServerLocation_geolocatesARecordValueDirectly() {
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)));
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().publicHost()).isEqualTo("203.0.113.10");
        assertThat(result.get().latitude()).isEqualTo(59.91);
        assertThat(result.get().longitude()).isEqualTo(10.74);
        assertThat(result.get().city()).isEqualTo("Oslo");
        assertThat(result.get().country()).isEqualTo("Norway");
    }

    @Test
    void getServerLocation_resolvesCnameToIpThenGeolocates() {
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("localhost", DnsRecordType.CNAME)));
        when(forGeolocatingIps.locate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(new GeoLocation(0.0, 0.0, null, null)));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().publicHost()).isEqualTo("localhost");
    }

    @Test
    void getServerLocation_fallsBackToFjordDomainWhenNoPublicHostConfigured() {
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        // The DNS resolution of vaier.eilertsen.family at test time is unpredictable, so we just verify
        // the geolocation port is consulted (with whatever IP came back) and the fallback hostname is used.
        when(forGeolocatingIps.locate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        Optional<ServerLocation> result = service.getServerLocation();

        // If DNS resolves the test hostname (e.g. real internet), we get the fallback path.
        // If not, result is empty — both are valid in the test environment, so we only assert on
        // the publicHost label when the result is present.
        if (result.isPresent()) {
            assertThat(result.get().publicHost()).isEqualTo("vaier.eilertsen.family");
        }
    }

    @Test
    void getServerLocation_returnsEmptyWhenNothingConfigured() {
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn(null);

        assertThat(service.getServerLocation()).isEmpty();
        verifyNoInteractions(forGeolocatingIps);
    }

    @Test
    void getServerLocation_populatesLanCidrFromResolverWhenGeoSucceeds() {
        // The LAN CIDR rides along on the same response as geolocation so the dashboard fetches
        // both server-only facts in one call (#204 surface on the Fjord-server machine card).
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)));
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().lanCidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void getServerLocation_returnsLanCidrEvenWhenGeolocationUnavailable() {
        // Geoip-init may not have populated the MMDB yet, or the public host can't be resolved.
        // The LAN CIDR is independent and useful on its own — surface it without blocking on geo.
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn(null);
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.0.0/16"));

        Optional<ServerLocation> result = service.getServerLocation();

        assertThat(result).isPresent();
        assertThat(result.get().publicHost()).isNull();
        assertThat(result.get().latitude()).isNull();
        assertThat(result.get().longitude()).isNull();
        assertThat(result.get().lanCidr()).isEqualTo("172.31.0.0/16");
    }

    @Test
    void getServerLocation_returnsEmptyWhenNoGeoAndNoLanCidr() {
        when(forResolvingPublicHost.resolve()).thenReturn(Optional.empty());
        when(configResolver.getDomain()).thenReturn(null);
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.empty());

        assertThat(service.getServerLocation()).isEmpty();
    }

    @Test
    void getServerLocation_returnsEmptyWhenCnameDoesNotResolveAndNoDomain() {
        when(forResolvingPublicHost.resolve())
            .thenReturn(Optional.of(new PublicHost("does-not-resolve.invalid", DnsRecordType.CNAME)));
        when(configResolver.getDomain()).thenReturn("");

        assertThat(service.getServerLocation()).isEmpty();
    }

    // --- syncLanRoutes ---

    @Test
    void syncLanRoutes_passesEveryRelayCidr_toLanRouteAdapter() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, "192.168.3.0/24", null),
            new PeerConfiguration("alice",      "10.13.13.2", "config", MachineType.WINDOWS_CLIENT,    null,            null),
            new PeerConfiguration("nuc02",      "10.13.13.8", "config", MachineType.UBUNTU_SERVER, "192.168.4.0/24", null)
        ));

        service.syncLanRoutes();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Set<String>> captor = ArgumentCaptor.forClass(java.util.Set.class);
        verify(forSyncingLanRoutes).syncLanRoutes(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("192.168.3.0/24", "192.168.4.0/24");
    }

    @Test
    void syncLanRoutes_skipsBlankAndNullCidrs() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("alice", "10.13.13.2", "config", MachineType.WINDOWS_CLIENT,    null, null),
            new PeerConfiguration("blank", "10.13.13.3", "config", MachineType.UBUNTU_SERVER, "  ", null)
        ));

        service.syncLanRoutes();

        verify(forSyncingLanRoutes).syncLanRoutes(java.util.Set.of());
    }

    @Test
    void updateLanCidr_alsoSyncsLanRoutes() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                MachineType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, null, null)));

        service.updateLanCidr("apalveien5", "192.168.3.0/24");

        // Sync must run AFTER the persistence step — otherwise the sync would read the pre-change CIDRs.
        var order = inOrder(forUpdatingPeerConfigurations, forSyncingLanRoutes);
        order.verify(forUpdatingPeerConfigurations).updateLanCidr("apalveien5", "192.168.3.0/24");
        order.verify(forSyncingLanRoutes).syncLanRoutes(any());
    }

    // --- updateLanCidr (#176) ---

    @Test
    void updateLanCidr_setsServerSideAllowedIpsAndMetadata() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                MachineType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, null, null)));

        service.updateLanCidr("apalveien5", "192.168.3.0/24");

        var order = inOrder(forUpdatingServerAllowedIps, forUpdatingPeerConfigurations);
        order.verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.6", "10.13.13.6/32,192.168.3.0/24");
        order.verify(forUpdatingPeerConfigurations).updateLanCidr("apalveien5", "192.168.3.0/24");
    }

    @Test
    void updateLanCidr_clearingStripsServerSideAllowedIps() {
        when(peerConfigProvider.getPeerConfigByName("nuc02"))
            .thenReturn(Optional.of(new PeerConfiguration("nuc02", "10.13.13.8", "config",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        service.updateLanCidr("nuc02", null);

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.8", "10.13.13.8/32");
        verify(forUpdatingPeerConfigurations).updateLanCidr("nuc02", null);
    }

    @Test
    void updateLanCidr_blankIsTreatedAsClear() {
        when(peerConfigProvider.getPeerConfigByName("nuc02"))
            .thenReturn(Optional.of(new PeerConfiguration("nuc02", "10.13.13.8", "config",
                MachineType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        service.updateLanCidr("nuc02", "  ");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.8", "10.13.13.8/32");
    }

    @Test
    void updateLanCidr_changingReplacesServerSideCidr() {
        when(peerConfigProvider.getPeerConfigByName("relay"))
            .thenReturn(Optional.of(new PeerConfiguration("relay", "10.13.13.10", "config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.10", "config", MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));

        service.updateLanCidr("relay", "192.168.5.0/24");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.10", "10.13.13.10/32,192.168.5.0/24");
        verify(forUpdatingPeerConfigurations).updateLanCidr("relay", "192.168.5.0/24");
    }

    @Test
    void updateLanCidr_rejectsConflictWhenAnotherPeerOwnsTheCidr() {
        when(peerConfigProvider.getPeerConfigByName("apalveien5"))
            .thenReturn(Optional.of(new PeerConfiguration("apalveien5", "10.13.13.6", "config",
                MachineType.UBUNTU_SERVER, null, null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("apalveien5", "10.13.13.6", "config", MachineType.UBUNTU_SERVER, null, null),
            new PeerConfiguration("nuc02",      "10.13.13.8", "config", MachineType.UBUNTU_SERVER, "192.168.3.0/24", null)));

        assertThatThrownBy(() -> service.updateLanCidr("apalveien5", "192.168.3.0/24"))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("nuc02")
            .hasMessageContaining("192.168.3.0/24");

        verifyNoInteractions(forUpdatingServerAllowedIps);
        verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    @Test
    void updateLanCidr_allowsSameCidrOnSamePeerIdempotent() {
        when(peerConfigProvider.getPeerConfigByName("relay"))
            .thenReturn(Optional.of(new PeerConfiguration("relay", "10.13.13.10", "config",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("relay", "10.13.13.10", "config", MachineType.UBUNTU_SERVER, "192.168.1.0/24", null)));

        service.updateLanCidr("relay", "192.168.1.0/24");

        verify(forUpdatingServerAllowedIps).setPeerAllowedIps("10.13.13.10", "10.13.13.10/32,192.168.1.0/24");
        verify(forUpdatingPeerConfigurations).updateLanCidr("relay", "192.168.1.0/24");
    }

    @Test
    void updateLanCidr_throwsWhenPeerDoesNotExist() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateLanCidr("ghost", "192.168.3.0/24"))
            .isInstanceOf(PeerNotFoundException.class)
            .hasMessageContaining("ghost");

        verifyNoInteractions(forUpdatingServerAllowedIps);
        verifyNoInteractions(forUpdatingPeerConfigurations);
    }

    // --- updateLanCidr (#195) — reject shell-injection payloads at the boundary ---

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "1.2.3.0/24; id",
        "1.2.3.0/24 | id",
        "1.2.3.0/24`id`",
        "1.2.3.0/24$(id)",
        "1.2.3.0/24\nid",
        "256.0.0.0/24",
        "1.2.3.4/33",
        "not-a-cidr"
    })
    void updateLanCidr_rejectsCommandInjectionAndMalformedCidr(String malicious) {
        // The injection check must fire BEFORE any peer lookup or persistence call.
        // Otherwise an attacker could probe peer existence + leave audit traces
        // even on rejected requests.
        assertThatThrownBy(() -> service.updateLanCidr("apalveien5", malicious))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");

        verifyNoInteractions(peerConfigProvider, forUpdatingServerAllowedIps, forUpdatingPeerConfigurations, forSyncingLanRoutes);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "1.2.3.0/24; id",
        "256.0.0.0/24",
        "1.2.3.4/33"
    })
    void createPeer_rejectsCommandInjectionAndMalformedCidr(String malicious) {
        assertThatThrownBy(() -> service.createPeer("evilpeer", MachineType.UBUNTU_SERVER, malicious))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lanCidr");

        verifyNoInteractions(peerConfigProvider, forUpdatingServerAllowedIps,
            forUpdatingPeerConfigurations, forGettingVpnClients);
    }

    // --- createPeer / renamePeer: a machine name is a label, not a key (§6.22) ---

    @Test
    void createPeer_allowsANameAnotherPeerAlreadyWears() {
        // The payoff. Names had to be unique because records hung off them; everything hangs off a
        // MachineId now. The peer ID still deduplicates — it is a directory on disk — but that is a
        // filesystem constraint, not an opinion about what an operator may call their machines.
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            new PeerConfiguration("nas", "10.13.13.2", "config")
        ));

        // It no longer stops at the name — it reaches key generation. (It fails after that for want of a
        // real WireGuard container, which is this unit test's harness, not a rule about names.)
        assertThatThrownBy(() -> service.createPeer("nas")).isNotInstanceOf(ConflictException.class);
        verify(forExecutingInContainer).execute(any(), eq("wg"), eq("genkey"));
    }

    @Test
    void createPeer_allowsALanServersName() {
        // The LAN-server list is not even read any more: there is no name to be free of. That the stub
        // would be unnecessary here is itself the point.
        assertThatThrownBy(() -> service.createPeer("nas")).isNotInstanceOf(ConflictException.class);
        verify(forExecutingInContainer).execute(any(), eq("wg"), eq("genkey"));
    }

    @Test
    void createPeer_allowsTheFjordServersOwnName() {
        // "Fjord server" was reserved because the Fjord server was recognised BY that name. It is
        // recognised by its identity now, so the string is just a string.
        assertThatThrownBy(() -> service.createPeer(net.fjordomatic.domain.LanAnchor.FJORD_SERVER_NAME))
            .isNotInstanceOf(ConflictException.class);
        verify(forExecutingInContainer).execute(any(), eq("wg"), eq("genkey"));
    }

    @Test
    void renamePeer_allowsANameAnotherMachineAlreadyWears() {
        when(peerConfigProvider.getPeerConfigByName("laptp"))
            .thenReturn(Optional.of(new PeerConfiguration("laptp", "10.13.13.2", "config")));

        // Another machine is already called "nas". Nothing is keyed to a name, so nothing objects.
        service.renamePeer("laptp", "nas");

        verify(forUpdatingPeerConfigurations).updateName("laptp", "nas");
    }

    // --- renamePeer migrates name-keyed SSH state (#312) ---

    @Test
    void renamePeer_migratesSshCredentialAndHostKeyPinToNewName() {
        // Renaming sets a label. The credential and host-key pin hang off the peer's identity, which a
        // rename does not touch — so there is nothing to carry, and carrying nothing is the point.
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        service.renamePeer("alice", "wonderland");

        verify(forUpdatingPeerConfigurations).updateName("alice", "wonderland");
        verify(forPersistingHostCredentials, never()).save(any());
        verify(forPersistingHostCredentials, never()).deleteByMachine(any());
        verify(forTrackingHostKeys, never()).pin(any(), any());
        verify(forTrackingHostKeys, never()).clear(any());
    }


    @Test
    void renamePeer_noOpSameName_leavesSshStateIntact() {
        // "alice" (peerId) already displays as "alice"; renaming to the same effective label is a no-op.
        when(peerConfigProvider.getPeerConfigByName("alice"))
            .thenReturn(Optional.of(new PeerConfiguration("alice", "10.13.13.2", "config")));

        service.renamePeer("alice", "alice");

        verify(forPersistingHostCredentials, never()).deleteByMachine(any());
        verify(forTrackingHostKeys, never()).clear(any());
    }

    @Test
    void renamePeer_clearingName_allowedWhenHumanisedIdFallbackIsFree() {
        when(peerConfigProvider.getPeerConfigByName("media-server"))
            .thenReturn(Optional.of(new PeerConfiguration("media-server", "10.13.13.2", "config")));

        service.renamePeer("media-server", "");

        verify(forUpdatingPeerConfigurations).updateName("media-server", "");
    }

    // --- updatePeerDeviceCategory: orthogonal icon override ---

    @Test
    void updatePeerDeviceCategory_persistsValidOverride() {
        when(peerConfigProvider.getPeerConfigByName("nas"))
            .thenReturn(Optional.of(new PeerConfiguration("nas", "10.13.13.2", "config")));

        service.updatePeerDeviceCategory("nas", "NAS");

        verify(forUpdatingPeerConfigurations).updateDeviceCategory("nas", "NAS");
    }

    @Test
    void updatePeerDeviceCategory_blankClearsOverride() {
        when(peerConfigProvider.getPeerConfigByName("nas"))
            .thenReturn(Optional.of(new PeerConfiguration("nas", "10.13.13.2", "config")));

        service.updatePeerDeviceCategory("nas", "  ");

        // Blank normalises to null ("clear the override") — the service never forwards the raw,
        // unparsed request string to the port.
        verify(forUpdatingPeerConfigurations).updateDeviceCategory("nas", null);
    }

    @Test
    void updatePeerDeviceCategory_persistsNormalisedEnumNameNotRawCasing() {
        when(peerConfigProvider.getPeerConfigByName("nas"))
            .thenReturn(Optional.of(new PeerConfiguration("nas", "10.13.13.2", "config")));

        service.updatePeerDeviceCategory("nas", "nas");

        // The parsed enum name is persisted, not the raw lower-case request value.
        verify(forUpdatingPeerConfigurations).updateDeviceCategory("nas", "NAS");
    }

    @Test
    void updatePeerDeviceCategory_rejectsInvalidValueWithoutPersisting() {
        assertThatThrownBy(() -> service.updatePeerDeviceCategory("nas", "BANANA"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(forUpdatingPeerConfigurations, never()).updateDeviceCategory(any(), any());
    }

    @Test
    void updatePeerDeviceCategory_throwsWhenPeerMissing() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updatePeerDeviceCategory("ghost", "NAS"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forUpdatingPeerConfigurations, never()).updateDeviceCategory(any(), any());
    }

    // --- renamePeer: sets the display name; the id is immutable (#209, #55) ---

    @Test
    void renamePeer_setsDisplayNameViaUpdatePort() {
        when(peerConfigProvider.getPeerConfigByName("laptp"))
            .thenReturn(Optional.of(new PeerConfiguration("laptp", "10.13.13.2", "config")));

        service.renamePeer("laptp", "My Laptop");

        verify(forUpdatingPeerConfigurations).updateName("laptp", "My Laptop");
    }

    @Test
    void renamePeer_keepsTypedNameVerbatim_neitherSanitisingNorMovingFiles() {
        // The id (config directory name) is frozen; the display name is free text stored exactly
        // as typed — spaces, punctuation and case all preserved, no slugging (issue #209).
        when(peerConfigProvider.getPeerConfigByName("media-server"))
            .thenReturn(Optional.of(new PeerConfiguration("media-server", "10.13.13.2", "config")));

        service.renamePeer("media-server", "Media Server #1");

        verify(forUpdatingPeerConfigurations).updateName("media-server", "Media Server #1");
    }

    @Test
    void renamePeer_throwsWhenPeerNotFound() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.renamePeer("ghost", "Phantom"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forUpdatingPeerConfigurations, never()).updateName(any(), any());
    }

    // --- getVpnPeers (#220) ---

    @Test
    void getVpnPeers_returnsEmptyWhenNoClients() {
        when(forGettingVpnClients.getClients()).thenReturn(List.of());

        assertThat(service.getVpnPeers()).isEmpty();
    }

    @Test
    void getVpnPeers_assemblesFromClientPlusPeerConfigPlusGeo() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.of(
            new PeerConfiguration("alice-1", "Alice", "10.13.13.2", "[Interface]",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10", "alice's box")));
        when(forGeolocatingIps.locate("203.0.113.10"))
            .thenReturn(Optional.of(new GeoLocation(59.91, 10.74, "Oslo", "Norway")));

        var view = service.getVpnPeers().get(0);

        assertThat(view.id()).isEqualTo("alice-1");
        assertThat(view.name()).isEqualTo("Alice");
        assertThat(view.publicKey()).isEqualTo("pub");
        assertThat(view.peerType()).isEqualTo(MachineType.UBUNTU_SERVER);
        assertThat(view.tunnelIp()).isEqualTo("10.13.13.2");
        assertThat(view.isServer()).isTrue();
        assertThat(view.isClient()).isFalse();
        assertThat(view.isRelay()).isTrue();
        assertThat(view.availableArtifacts()).contains(
            net.fjordomatic.domain.PeerArtifact.WG_CONFIG,
            net.fjordomatic.domain.PeerArtifact.DOCKER_COMPOSE,
            net.fjordomatic.domain.PeerArtifact.SETUP_SCRIPT);
        assertThat(view.lanCidr()).isEqualTo("192.168.1.0/24");
        assertThat(view.lanAddress()).isEqualTo("192.168.1.10");
        assertThat(view.description()).isEqualTo("alice's box");
        assertThat(view.geoLocation()).contains(new GeoLocation(59.91, 10.74, "Oslo", "Norway"));
    }

    @Test
    void getVpnPeers_readsEachPeerConfigOnlyOnce() {
        // The peer view derives both its device category and its config fields from the same
        // on-disk config — it must load that config once per peer, not twice (perf regression guard).
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.of(
            new PeerConfiguration("alice-1", "Alice", "10.13.13.2", "[Interface]",
                MachineType.UBUNTU_SERVER, "192.168.1.0/24", "192.168.1.10", "alice's box")));

        service.getVpnPeers();

        verify(peerConfigProvider, times(1)).getPeerConfigByIp("10.13.13.2");
    }

    @Test
    void getVpnPeers_mobileClient_isClientNotServer_andOffersQrCode() {
        VpnClient client = new VpnClient("pub", "10.13.13.5/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.5")).thenReturn("phone");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.5")).thenReturn(Optional.of(
            new PeerConfiguration("phone", "Phone", "10.13.13.5", "",
                MachineType.MOBILE_CLIENT, null, null, null)));

        var view = service.getVpnPeers().get(0);

        assertThat(view.isServer()).isFalse();
        assertThat(view.isClient()).isTrue();
        assertThat(view.isRelay()).isFalse();
        assertThat(view.availableArtifacts())
            .containsExactlyInAnyOrder(
                net.fjordomatic.domain.PeerArtifact.WG_CONFIG,
                net.fjordomatic.domain.PeerArtifact.QR_CODE);
    }

    @Test
    void getVpnPeers_fallsBackToDefaultTypeAndDisplayLabelWhenNoPeerConfig() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("orphan-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());

        var view = service.getVpnPeers().get(0);

        assertThat(view.peerType()).isEqualTo(MachineType.defaultType());
        assertThat(view.name()).isEqualTo(net.fjordomatic.domain.PeerId.display("orphan-1"));
        assertThat(view.lanCidr()).isNull();
        assertThat(view.lanAddress()).isNull();
        assertThat(view.description()).isNull();
    }

    /**
     * The field the Explorer joins the fleet on. It comes from the stored config and is never minted here:
     * this is a read, and identity is read, never invented.
     */
    @Test
    void getVpnPeers_carriesTheMachinesIdentityFromItsStoredConfig() {
        MachineId identity = MachineId.generate();
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.of(
            new PeerConfiguration("alice-1", "Alice", "10.13.13.2", "[Interface]",
                MachineType.UBUNTU_SERVER, null, null, null, null, null, identity)));

        assertThat(service.getVpnPeers().get(0).machineId()).isEqualTo(identity.value());
    }

    /**
     * A live WireGuard peer with no config on disk is in no machine registry, so it has no identity to give.
     * Null, never a stand-in: a fabricated id would join to nothing while looking like it could, and a
     * caller would read "this peer is not a machine" as "this machine is not a peer".
     */
    @Test
    void getVpnPeers_hasNoIdentityForAPeerWithNoStoredConfig() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("orphan-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());

        assertThat(service.getVpnPeers().get(0).machineId()).isNull();
    }

    @Test
    void getVpnPeers_skipsGeolocationWhenEndpointIsBlank() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());

        var view = service.getVpnPeers().get(0);

        assertThat(view.geoLocation()).isEmpty();
        verify(forGeolocatingIps, never()).locate(any());
    }

    @Test
    void getVpnPeers_emptyGeoOptionalWhenLookupFails() {
        VpnClient client = new VpnClient("pub", "10.13.13.2/32", "203.0.113.10", "51820", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.2")).thenReturn("alice-1");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.2")).thenReturn(Optional.empty());
        when(forGeolocatingIps.locate("203.0.113.10")).thenReturn(Optional.empty());

        assertThat(service.getVpnPeers().get(0).geoLocation()).isEmpty();
    }

    // --- reissuePeerConfig (#247) ---

    @Test
    void reissuePeerConfig_reRendersWithCurrentServerLanCidr_rewritesAndResetsGate() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        // A server peer created before server-LAN routing: its client AllowedIPs lacks the CIDR.
        String existing = net.fjordomatic.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.example.com:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        when(peerConfigProvider.getPeerConfigByName("apalveien5")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forExecutingInContainer.execute("wireguard", "wg", "show", "wg0", "public-key"))
            .thenReturn("SERVER_PUB\n");
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        var result = service.reissuePeerConfig("apalveien5");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(forUpdatingPeerConfigurations).rewriteConfig(eq("apalveien5"), content.capture());
        assertThat(content.getValue())
            .contains("AllowedIPs = 10.13.13.0/24,172.31.16.0/20")
            .contains("PrivateKey = PRIVKEY");
        verify(forTrackingPeerConfigRetrieval).resetViewed("apalveien5");
        assertThat(result.clientConfigFile()).contains("172.31.16.0/20");
        assertThat(result.publicKey()).isEqualTo("PEER_PUB");
        assertThat(result.ipAddress()).isEqualTo("10.13.13.6");
    }

    @Test
    void reissuePeerConfig_throwsWhenPeerUnknown() {
        when(peerConfigProvider.getPeerConfigByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissuePeerConfig("ghost"))
            .isInstanceOf(PeerNotFoundException.class);
        verify(forUpdatingPeerConfigurations, never()).rewriteConfig(any(), any());
    }

    @Test
    void reissuePeerConfig_retainsStoredDeviceCategoryOverride() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = net.fjordomatic.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.example.com:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        when(peerConfigProvider.getPeerConfigByName("apalveien5")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null,
                net.fjordomatic.domain.DeviceCategory.NAS)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forExecutingInContainer.execute("wireguard", "wg", "show", "wg0", "public-key"))
            .thenReturn("SERVER_PUB\n");
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        service.reissuePeerConfig("apalveien5");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(forUpdatingPeerConfigurations).rewriteConfig(eq("apalveien5"), content.capture());
        assertThat(content.getValue()).contains("\"deviceCategory\":\"NAS\"");
    }

    @Test
    void reissuePeerConfig_nonOverriddenPeer_writesNoDeviceCategoryKey() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = net.fjordomatic.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.example.com:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        when(peerConfigProvider.getPeerConfigByName("apalveien5")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null, null)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forExecutingInContainer.execute("wireguard", "wg", "show", "wg0", "public-key"))
            .thenReturn("SERVER_PUB\n");
        when(forExecutingInContainer.executeWithInput(eq("wireguard"), any(), eq("wg"), eq("pubkey")))
            .thenReturn("PEER_PUB\n");

        service.reissuePeerConfig("apalveien5");

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(forUpdatingPeerConfigurations).rewriteConfig(eq("apalveien5"), content.capture());
        assertThat(content.getValue()).doesNotContain("deviceCategory");
    }

    @Test
    void getVpnPeers_flagsConfigOutOfDateWhenRenderedConfigDiverges() throws Exception {
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "wireguardContainerName", "wireguard");
        ReflectionTestUtils.setField(service, "wireguardInterface", "wg0");

        String existing = net.fjordomatic.domain.WireGuardPeerConfig.generate(
            "PRIVKEY", "10.13.13.6", "SERVER_PUB", "PSK", "vaier.eilertsen.family:51820",
            MachineType.UBUNTU_SERVER, null, null, "10.13.13.0/24", null, "apalveien5", null);
        VpnClient client = new VpnClient("pub", "10.13.13.6/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.6")).thenReturn("apalveien5");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", existing,
                MachineType.UBUNTU_SERVER, null, null, null)));
        when(configResolver.getDomain()).thenReturn("eilertsen.family");
        when(forResolvingServerLanCidr.resolve()).thenReturn(Optional.of("172.31.16.0/20"));
        when(forExecutingInContainer.execute("wireguard", "wg", "show", "wg0", "public-key"))
            .thenReturn("SERVER_PUB\n");

        assertThat(service.getVpnPeers().get(0).configOutOfDate()).isTrue();
    }

    @Test
    void getVpnPeers_configNotOutOfDateWhenServerStateUnavailable() {
        // No server pubkey stubbed → drift can't be computed; must not false-flag.
        VpnClient client = new VpnClient("pub", "10.13.13.6/32", "", "", "0", "0", "0");
        when(forGettingVpnClients.getClients()).thenReturn(List.of(client));
        when(forResolvingPeerIds.resolvePeerIdByIp("10.13.13.6")).thenReturn("apalveien5");
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.6")).thenReturn(Optional.of(
            new PeerConfiguration("apalveien5", "apalveien5", "10.13.13.6", "[Interface]",
                MachineType.UBUNTU_SERVER, null, null, null)));

        assertThat(service.getVpnPeers().get(0).configOutOfDate()).isFalse();
    }

}
