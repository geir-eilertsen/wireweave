package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LockoutWarningTest {

    private static final TrustedNetworks TRUSTED =
        TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of("192.168.3.0/24"));

    private static final BlockDecision VPN_PEER = BlockDecision.builder()
        .id(1L).scenario("crowdsecurity/http-probing").sourceIp("10.13.13.6").type("ban")
        .duration("3h59m48s").build();
    private static final BlockDecision RELAY_LAN = BlockDecision.builder()
        .id(2L).scenario("crowdsecurity/ssh-bf").sourceIp("192.168.3.40").type("ban")
        .duration("4h0m0s").build();
    private static final BlockDecision STRANGER = BlockDecision.builder()
        .id(3L).scenario("crowdsecurity/http-probing").sourceIp("195.178.110.155").type("ban")
        .duration("3h0m40s").country("BG").asnOrg("Techoff Srv Limited").build();

    @Test
    void from_keepsOnlyBansOnTheOperatorsOwnNetworks() {
        LockoutWarning warning = LockoutWarning.from(List.of(VPN_PEER, STRANGER, RELAY_LAN), TRUSTED);

        assertThat(warning.decisions()).containsExactly(VPN_PEER, RELAY_LAN);
        assertThat(warning.worthSending()).isTrue();
    }

    /**
     * The ordinary sweep. Every block decision in it is a stranger, so nothing is wrong with the allowlist
     * and no alarm is raised — the same silence the breach-attempt rollup keeps.
     */
    @Test
    void from_isSilentWhenNoneOfTheOperatorsOwnNetworksAreBlocked() {
        LockoutWarning warning = LockoutWarning.from(List.of(STRANGER), TRUSTED);

        assertThat(warning.decisions()).isEmpty();
        assertThat(warning.worthSending()).isFalse();
    }

    /**
     * The scenario is irrelevant here — blind scanning from inside the VPN subnet still means the
     * allowlist has stopped protecting the operator, which is the whole point of the alarm.
     */
    @Test
    void from_ignoresWhatTheScenarioWas() {
        assertThat(LockoutWarning.from(List.of(VPN_PEER), TRUSTED).decisions()).containsExactly(VPN_PEER);
    }

    @Test
    void from_withoutTrustedNetworksRaisesNothing() {
        assertThat(LockoutWarning.from(List.of(VPN_PEER), null).worthSending()).isFalse();
    }

    /** The subject must not read as an attack — it has to say, in the first words, whose network it is. */
    @Test
    void subject_namesTheOperatorsOwnBlockedAddress() {
        assertThat(LockoutWarning.from(List.of(VPN_PEER), TRUSTED).subject())
            .isEqualTo("[Vaier] Lockout warning: your own 10.13.13.6 is blocked at the edge")
            .doesNotContain("Breach attempt");
    }

    @Test
    void subject_countsSeveralOfTheOperatorsOwnAddresses() {
        assertThat(LockoutWarning.from(List.of(VPN_PEER, RELAY_LAN), TRUSTED).subject())
            .isEqualTo("[Vaier] Lockout warning: 2 of your own addresses are blocked at the edge");
    }

    @Test
    void body_explainsTheAllowlistFailedAndListsEveryAddress() {
        String body = LockoutWarning.from(List.of(VPN_PEER, RELAY_LAN), TRUSTED).body(null);

        assertThat(body)
            .contains("trusted networks")
            .contains(VPN_PEER.label())
            .contains(RELAY_LAN.label())
            .doesNotContain("break in");
    }

    @Test
    void body_namesTheAllowlistsContents_inTheOperatorsWords() {
        // This body lands in an inbox, so it is UI copy under a different name. UBIQUITOUS_LANGUAGE.md §17:
        // "VPN subnet" and "relay" are internal vocabulary — the operator has a VPN and machines with
        // networks behind them, and that is what the allowlist is made of.
        String body = LockoutWarning.from(List.of(VPN_PEER), TRUSTED).body(null);

        assertThat(body)
            .contains("trusted networks allowlist")
            .doesNotContain("VPN subnet")
            .doesNotContain("relay");
    }

    @Test
    void body_tellsTheOperatorHowToGetBackIn() {
        String body = LockoutWarning.from(List.of(VPN_PEER), TRUSTED).body(null);

        assertThat(body).contains("Security view").contains("lift");
    }

    @Test
    void body_includesTheUiLinkWhenBaseDomainIsGiven() {
        assertThat(LockoutWarning.from(List.of(VPN_PEER), TRUSTED).body("example.com"))
            .contains("https://vaier.example.com/");
    }

    @Test
    void body_omitsTheUiLinkWhenBaseDomainIsBlank() {
        assertThat(LockoutWarning.from(List.of(VPN_PEER), TRUSTED).body(" ")).doesNotContain("Vaier UI");
    }
}
