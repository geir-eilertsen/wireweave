package net.vaier.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PeerLivenessTest {

    private static final String RECENT = String.valueOf(System.currentTimeMillis() / 1000 - 30);

    private static VpnClient peer(String key, String handshake, String rx) {
        return new VpnClient(key, "10.13.13.3/32", "1.2.3.4", "51820", handshake, rx, "0");
    }

    @Test
    void of_keepsOnlyTheConnectedPeers() {
        PeerLiveness liveness = PeerLiveness.of(List.of(peer("up", RECENT, "1"), peer("down", "0", "1")));

        assertThat(liveness.connectedPublicKeys()).containsExactly("up");
    }

    @Test
    void differsFrom_isTrueWhenAPeerComesUp() {
        PeerLiveness before = PeerLiveness.of(List.of(peer("colina", "0", "1")));
        PeerLiveness after = PeerLiveness.of(List.of(peer("colina", RECENT, "1")));

        assertThat(after.differsFrom(before)).isTrue();
    }

    @Test
    void differsFrom_isTrueWhenAPeerGoesAway() {
        PeerLiveness before = PeerLiveness.of(List.of(peer("colina", RECENT, "1")));
        PeerLiveness after = PeerLiveness.of(List.of());

        assertThat(after.differsFrom(before)).isTrue();
    }

    @Test
    void differsFrom_ignoresTrafficThatMerelyKeepsFlowing() {
        PeerLiveness before = PeerLiveness.of(List.of(peer("colina", RECENT, "100")));
        PeerLiveness after = PeerLiveness.of(List.of(peer("colina", RECENT, "200")));

        assertThat(after.differsFrom(before)).isFalse();
    }
}
