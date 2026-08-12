package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LastServiceReachedTest {

    private static final Instant NOON = Instant.parse("2026-08-11T12:00:00Z");
    private static final String VPN_SUBNET = "10.13.13.0/24";
    private static final MachineId PHONE = TestMachineIds.of("phone");

    private final ForGettingPeerConfigurations peers = mock(ForGettingPeerConfigurations.class);

    private static PeerConfiguration peer(String id, String tunnelIp, MachineId machineId) {
        return new PeerConfiguration(id, id, tunnelIp, "", MachineType.MOBILE_CLIENT, null, null, null,
            null, null, machineId);
    }

    @Test
    void attributesTheAccessToTheMachineHoldingThatTunnelIp() {
        when(peers.getPeerConfigByIp("10.13.13.4")).thenReturn(Optional.of(peer("phone", "10.13.13.4", PHONE)));

        Optional<LastServiceReached> reached = LastServiceReached.reachedOverTheTunnel(
            "10.13.13.4", VPN_SUBNET, "grafana.example.com", NOON, peers);

        assertThat(reached).contains(new LastServiceReached(PHONE, "grafana.example.com", NOON));
    }

    /**
     * The whole point of the feature. A carrier address is shared by thousands of subscribers, so it
     * identifies a person and never a device — guessing from it is the error this exists to stop making.
     */
    @Test
    void attributesNothingWhenTheCallerIsNotOnTheTunnel() {
        Optional<LastServiceReached> reached = LastServiceReached.reachedOverTheTunnel(
            "203.0.113.7", VPN_SUBNET, "grafana.example.com", NOON, peers);

        assertThat(reached).isEmpty();
    }

    /** Off-tunnel addresses never even ask the store — the lookup rides on the forward-auth hot path. */
    @Test
    void doesNotLookUpAPeerForAnAddressThatCannotBeATunnelIp() {
        LastServiceReached.reachedOverTheTunnel("192.168.1.20", VPN_SUBNET, "grafana.example.com", NOON, peers);

        verify(peers, never()).getPeerConfigByIp(any());
    }

    /** An address in the subnet that no peer holds is nobody's — a gap, never a guess. */
    @Test
    void attributesNothingWhenNoPeerHoldsThatTunnelIp() {
        when(peers.getPeerConfigByIp("10.13.13.9")).thenReturn(Optional.empty());

        assertThat(LastServiceReached.reachedOverTheTunnel(
            "10.13.13.9", VPN_SUBNET, "grafana.example.com", NOON, peers)).isEmpty();
    }

    @Test
    void attributesNothingWithoutAHostToName() {
        when(peers.getPeerConfigByIp("10.13.13.4")).thenReturn(Optional.of(peer("phone", "10.13.13.4", PHONE)));

        assertThat(LastServiceReached.reachedOverTheTunnel("10.13.13.4", VPN_SUBNET, "  ", NOON, peers))
            .isEmpty();
    }

    /** {@code X-Forwarded-Host} carries the port when there is one, and case is the caller's whim. */
    @Test
    void readsTheHostAsAName_withoutItsPortAndInLowerCase() {
        when(peers.getPeerConfigByIp("10.13.13.4")).thenReturn(Optional.of(peer("phone", "10.13.13.4", PHONE)));

        Optional<LastServiceReached> reached = LastServiceReached.reachedOverTheTunnel(
            "10.13.13.4", VPN_SUBNET, "Grafana.Example.COM:443", NOON, peers);

        assertThat(reached).map(LastServiceReached::host).contains("grafana.example.com");
    }

    /** A misconfigured subnet must not turn every carrier address into somebody's phone. */
    @Test
    void attributesNothingWhenTheVpnSubnetWillNotParse() {
        assertThat(LastServiceReached.reachedOverTheTunnel(
            "10.13.13.4", "not-a-cidr", "grafana.example.com", NOON, peers)).isEmpty();
    }

    @Test
    void attributesNothingWhenThereIsNoCallerAtAll() {
        assertThat(LastServiceReached.reachedOverTheTunnel(
            null, VPN_SUBNET, "grafana.example.com", NOON, peers)).isEmpty();
    }

    @Test
    void knowsWhichOfTwoReachesIsTheMoreRecent() {
        LastServiceReached earlier = new LastServiceReached(PHONE, "grafana.example.com", NOON);
        LastServiceReached later = new LastServiceReached(PHONE, "plex.example.com", NOON.plusSeconds(60));

        assertThat(later.isNewerThan(earlier)).isTrue();
        assertThat(earlier.isNewerThan(later)).isFalse();
        assertThat(later.isNewerThan(null)).isTrue();
    }

    @Test
    void isForTheMachineItNames_andNoOther() {
        LastServiceReached reached = new LastServiceReached(PHONE, "grafana.example.com", NOON);

        assertThat(reached.isFor(PHONE)).isTrue();
        assertThat(reached.isFor(TestMachineIds.of("laptop"))).isFalse();
        assertThat(reached.isFor(null)).isFalse();
    }
}
