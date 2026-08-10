package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which hop of {@code X-Forwarded-For} to believe is a decision, not plumbing: believe the wrong one and
 * an attacker names their own address by sending the header themselves. It lived in
 * {@code LaunchpadRestController} and is now the domain's, so the forward-auth path and the launchpad
 * cannot drift apart on it.
 */
class CallerIpTest {

    private static final String TRUSTED = "172.20.0.0/16";

    @Test
    void takesTheForwardedForHeaderWhenTheConnectionCameFromTheTrustedProxy() {
        assertThat(CallerIp.of("172.20.0.5", "203.0.113.7", TRUSTED).value()).isEqualTo("203.0.113.7");
    }

    @Test
    void takesTheLeftmostHopWhenTheHeaderCarriesAChain() {
        // Leftmost is the original client; everything after it is a proxy that appended itself.
        assertThat(CallerIp.of("172.20.0.5", "203.0.113.7, 10.0.0.1", TRUSTED).value())
            .isEqualTo("203.0.113.7");
    }

    @Test
    void trimsWhitespaceAroundTheHopItBelieves() {
        assertThat(CallerIp.of("172.20.0.5", "  203.0.113.7 , 10.0.0.1", TRUSTED).value())
            .isEqualTo("203.0.113.7");
    }

    /**
     * The whole point of the gate. A request arriving straight from the internet can set any
     * {@code X-Forwarded-For} it likes, so its own address is the only thing worth believing.
     */
    @Test
    void ignoresTheForwardedForHeaderWhenTheConnectionDidNotComeFromTheTrustedProxy() {
        assertThat(CallerIp.of("203.0.113.99", "1.2.3.4", TRUSTED).value()).isEqualTo("203.0.113.99");
    }

    @Test
    void fallsBackToTheRemoteAddressWhenThereIsNoForwardedForHeader() {
        assertThat(CallerIp.of("172.20.0.5", null, TRUSTED).value()).isEqualTo("172.20.0.5");
        assertThat(CallerIp.of("172.20.0.5", "   ", TRUSTED).value()).isEqualTo("172.20.0.5");
    }

    @Test
    void survivesARequestWithNoAddressAtAll() {
        assertThat(CallerIp.of(null, "1.2.3.4", TRUSTED).value()).isNull();
    }

    /**
     * Defence in depth. The caller ip is handed to the geolocation adapter, which resolves anything that is
     * not an IP literal via DNS — a blocking lookup on the endpoint that authenticates every request to
     * every gated service. Traefik makes a hostname unreachable here today; believing only a literal means
     * it stays unreachable if that ever changes.
     */
    @Test
    void believesAForwardedHopOnlyWhenItIsAnAddressRatherThanAName() {
        assertThat(CallerIp.of("172.20.0.5", "evil.example.com", TRUSTED).value())
            .isEqualTo("172.20.0.5");
        assertThat(CallerIp.of("172.20.0.5", "not-an-ip, 203.0.113.7", TRUSTED).value())
            .isEqualTo("172.20.0.5");
        assertThat(CallerIp.of("172.20.0.5", "203.0.113.999", TRUSTED).value())
            .isEqualTo("172.20.0.5");
    }

    /**
     * A typo in the trusted-proxy CIDR used to throw straight out of the launchpad, which has no try/catch:
     * one bad property, and the page 500s. The rule lives here now, so the failure does too — and the safe
     * answer to "can I trust this header?" when the boundary is unreadable is no.
     */
    @Test
    void fallsBackToTheRemoteAddressWhenTheTrustedProxyCidrIsUnreadable() {
        assertThat(CallerIp.of("172.20.0.5", "203.0.113.7", "not-a-cidr").value())
            .isEqualTo("172.20.0.5");
        assertThat(CallerIp.of("172.20.0.5", "203.0.113.7", "172.20.0.0/99").value())
            .isEqualTo("172.20.0.5");
        assertThat(CallerIp.of("172.20.0.5", "203.0.113.7", "").value()).isEqualTo("172.20.0.5");
    }
}
