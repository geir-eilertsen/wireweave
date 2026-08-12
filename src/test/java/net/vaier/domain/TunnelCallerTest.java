package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TunnelCallerTest {

    private static final String VPN_SUBNET = "10.13.13.0/24";
    private static final MachineId PHONE = TestMachineIds.of("phone");

    private final ForGettingPeerConfigurations peers = mock(ForGettingPeerConfigurations.class);

    private static PeerConfiguration peer(String id, String tunnelIp, MachineId machineId) {
        return new PeerConfiguration(id, id, tunnelIp, "", MachineType.MOBILE_CLIENT, null, null, null,
            null, null, machineId);
    }

    @Test
    void namesTheMachineHoldingThatTunnelIp() {
        when(peers.getPeerConfigByIp("10.13.13.4"))
            .thenReturn(Optional.of(peer("phone", "10.13.13.4", PHONE)));

        assertThat(TunnelCaller.machineFor("10.13.13.4", VPN_SUBNET, peers)).contains(PHONE);
    }

    /**
     * The rule's load-bearing half: a carrier address the peer store happens to match is still not a
     * tunnel identity. Thousands of subscribers share it, so it names a person and never a device.
     */
    @Test
    void namesNoMachineForAnAddressOutsideTheSubnetEvenWhenAPeerWouldMatchIt() {
        when(peers.getPeerConfigByIp(any()))
            .thenReturn(Optional.of(peer("phone", "10.13.13.4", PHONE)));

        assertThat(TunnelCaller.machineFor("77.16.37.23", VPN_SUBNET, peers)).isEmpty();
    }

    /** Off-tunnel addresses never even ask the store — this rides on the forward-auth hot path. */
    @Test
    void doesNotLookUpAPeerForAnAddressThatCannotBeATunnelIp() {
        TunnelCaller.machineFor("192.168.1.20", VPN_SUBNET, peers);

        verify(peers, never()).getPeerConfigByIp(any());
    }

    /** An address in the subnet that no peer holds is nobody's — a gap, never a guess. */
    @Test
    void namesNoMachineWhenNoPeerHoldsThatTunnelIp() {
        when(peers.getPeerConfigByIp("10.13.13.9")).thenReturn(Optional.empty());

        assertThat(TunnelCaller.machineFor("10.13.13.9", VPN_SUBNET, peers)).isEmpty();
    }

    /** A misconfigured subnet must not turn every carrier address into somebody's phone. */
    @Test
    void namesNoMachineWhenTheVpnSubnetWillNotParse() {
        assertThat(TunnelCaller.machineFor("10.13.13.4", "not-a-cidr", peers)).isEmpty();
        assertThat(TunnelCaller.machineFor("10.13.13.4", null, peers)).isEmpty();
    }

    @Test
    void namesNoMachineWhenThereIsNoCallerAtAll() {
        assertThat(TunnelCaller.machineFor(null, VPN_SUBNET, peers)).isEmpty();
        assertThat(TunnelCaller.machineFor("  ", VPN_SUBNET, peers)).isEmpty();
    }
}
