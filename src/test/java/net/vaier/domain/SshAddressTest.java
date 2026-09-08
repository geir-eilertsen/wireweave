package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Where Vaier opens the SSH connection for a machine — a decision by machine kind, so it lives in the
 * domain and both the web terminal and the Explorer inherit the same answer.
 */
@ExtendWith(MockitoExtension.class)
class SshAddressTest {

    @Mock ForGettingPeerConfigurations peers;
    @Mock ForPersistingLanServers lanServers;
    @Mock ForResolvingVaierServerSshAddress vaierServer;

    private static final MachineId VAIER_SERVER = TestMachineIds.of("Vaier server");

    /** Where the machine {@code id} answers. The Vaier server's own id is supplied, so it can recognise itself. */
    private String addressOf(MachineId id) {
        return SshAddress.of(id, peers, lanServers, vaierServer, VAIER_SERVER);
    }

    private static PeerConfiguration peer(String name, String ip) {
        return new PeerConfiguration(name, name, ip, "", MachineType.UBUNTU_SERVER, null, null, null,
            null, null, TestMachineIds.of(name), null);
    }

    @Test
    void aVpnPeer_isReachedAtItsTunnelIp() {
        when(peers.getAllPeerConfigs()).thenReturn(List.of(peer("nuc", "10.13.13.9")));

        assertThat(addressOf(TestMachineIds.of("nuc"))).isEqualTo("10.13.13.9");
    }

    @Test
    void aLanServer_isReachedAtItsLanAddress() {
        lenient().when(peers.getAllPeerConfigs()).thenReturn(List.of());
        when(lanServers.getAll()).thenReturn(List.of(new LanServer("nas", "192.168.3.50", true, 2375,
            null, null, null, TestMachineIds.of("nas"))));

        assertThat(addressOf(TestMachineIds.of("nas"))).isEqualTo("192.168.3.50");
    }

    @Test
    void theVaierServerHost_isReachedAtItsResolvedHostAddress() {
        // The Vaier host is neither a peer nor a LAN server, so its address cannot be read from config.
        when(vaierServer.resolve()).thenReturn("172.17.0.1");

        assertThat(addressOf(VAIER_SERVER)).isEqualTo("172.17.0.1");
    }

    /**
     * Two machines may share a NAME — that is the whole point of keying on identity — so a peer and a LAN
     * server called the same thing are simply two machines, each reached at its own address. The old
     * "peer wins" tie-break existed only because a name could not tell them apart.
     */
    @Test
    void aPeerAndALanServerSharingAName_areTwoMachines_eachAtItsOwnAddress() {
        lenient().when(peers.getAllPeerConfigs()).thenReturn(List.of(peer("nas", "10.13.13.4")));
        lenient().when(lanServers.getAll()).thenReturn(List.of(new LanServer("nas", "192.168.3.50", true,
            2375, null, null, null, TestMachineIds.of("the other nas"))));

        assertThat(addressOf(TestMachineIds.of("nas"))).isEqualTo("10.13.13.4");
        assertThat(addressOf(TestMachineIds.of("the other nas"))).isEqualTo("192.168.3.50");
    }

    @Test
    void aMachineThatIsNeither_doesNotExist() {
        MachineId ghost = TestMachineIds.of("ghost");
        when(peers.getAllPeerConfigs()).thenReturn(List.of());
        when(lanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> addressOf(ghost))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(ghost.value());
    }

    /**
     * A Vaier that has not been assigned its own id yet cannot recognise itself, and must say so rather than
     * fall through and report the host address for whatever id it was handed.
     */
    @Test
    void withNoVaierServerIdentityYet_theHostIsNotReachableByGuess() {
        MachineId anything = TestMachineIds.of("anything");
        when(peers.getAllPeerConfigs()).thenReturn(List.of());
        when(lanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> SshAddress.of(anything, peers, lanServers, vaierServer, null))
            .isInstanceOf(NotFoundException.class);
    }
}
