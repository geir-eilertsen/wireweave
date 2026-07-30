package net.vaier.domain.port;

import net.vaier.domain.MachineType;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllLanCidrsTest {

    private static PeerConfiguration peer(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    @Test
    void allLanCidrs_collectsEveryNonBlankLanCidr() {
        List<PeerConfiguration> peers = List.of(
            peer("alice", "192.168.1.0/24"),
            peer("bob", "192.168.2.0/24"));

        assertThat(ForGettingPeerConfigurations.allLanCidrs(peers))
            .containsExactlyInAnyOrder("192.168.1.0/24", "192.168.2.0/24");
    }

    @Test
    void allLanCidrs_filtersOutNullAndBlankLanCidrs() {
        List<PeerConfiguration> peers = List.of(
            peer("alice", "192.168.1.0/24"),
            peer("bob", null),
            peer("carol", "  "));

        assertThat(ForGettingPeerConfigurations.allLanCidrs(peers)).containsExactly("192.168.1.0/24");
    }

    @Test
    void allLanCidrs_dedupesRepeatedCidrs() {
        List<PeerConfiguration> peers = List.of(
            peer("alice", "192.168.1.0/24"),
            peer("bob", "192.168.1.0/24"));

        assertThat(ForGettingPeerConfigurations.allLanCidrs(peers)).containsExactly("192.168.1.0/24");
    }

    @Test
    void allLanCidrs_emptyForNoPeers() {
        assertThat(ForGettingPeerConfigurations.allLanCidrs(List.of())).isEmpty();
    }
}
