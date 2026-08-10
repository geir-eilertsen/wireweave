package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one statement of "never route the host's own network into the tunnel". It existed for a long time
 * only as generated bash inside the {@link SetupScriptGuard setup-script guard} — a rule Fjord could
 * enforce on a machine it was about to reconfigure, but could not <em>ask itself</em> before offering a
 * route. #333 needs to ask it in Java, and a second copy of a rule that severs uplinks when it is wrong
 * is not something to keep two of.
 */
class UplinkGuardTest {

    @Test
    void wouldBlackhole_aCidrHoldingTheUplinkAddress_isRefused() {
        // The EC2 host answers on 172.31.37.204. Route 172.31.32.0/20 into wg0 there and the box loses
        // the network it is reached on — the exact accident of 2026-07-23.
        assertThat(UplinkGuard.wouldBlackhole("172.31.32.0/20", "172.31.37.204")).isTrue();
        assertThat(UplinkGuard.wouldBlackhole("172.31.0.0/16", "172.31.37.204")).isTrue();
        assertThat(UplinkGuard.wouldBlackhole("0.0.0.0/0", "172.31.37.204")).isTrue();
    }

    @Test
    void wouldBlackhole_aCidrElsewhere_isFine() {
        assertThat(UplinkGuard.wouldBlackhole("192.168.1.0/24", "172.31.37.204")).isFalse();
        assertThat(UplinkGuard.wouldBlackhole("10.13.13.0/24", "192.168.1.10")).isFalse();
    }

    @Test
    void wouldBlackhole_withNothingToJudgeAgainst_provesNothing() {
        // Not knowing an address is not evidence of danger. Refusing on "unknown" is how a guard turns
        // into a feature that silently never fires.
        assertThat(UplinkGuard.wouldBlackhole("192.168.1.0/24", null)).isFalse();
        assertThat(UplinkGuard.wouldBlackhole("192.168.1.0/24", "  ")).isFalse();
        assertThat(UplinkGuard.wouldBlackhole(null, "192.168.1.10")).isFalse();
        assertThat(UplinkGuard.wouldBlackhole("not-a-cidr", "192.168.1.10")).isFalse();
        assertThat(UplinkGuard.wouldBlackhole("192.168.1.0/24", "not-an-address")).isFalse();
    }

    @Test
    void shellRefusal_isTheSameRuleRunOnTheTargetHost() {
        String shell = UplinkGuard.shellRefusal(List.of("10.13.13.0/24", "172.31.32.0/20"));

        assertThat(shell).contains("ip route show default");
        assertThat(shell).contains("'10.13.13.0/24'").contains("'172.31.32.0/20'");
        assertThat(shell).contains("vaier_in_cidr");
    }

    @Test
    void shellHelpers_areStillPureBashArithmetic() {
        // No python, no ipcalc — a minimal host must be able to run the guard.
        assertThat(UplinkGuard.shellHelpers()).contains("vaier_in_cidr").contains("vaier_ip_to_int");
        assertThat(UplinkGuard.shellHelpers()).doesNotContain("python").doesNotContain("ipcalc");
    }
}
