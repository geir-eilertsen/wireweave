package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a finished command run reads as an upgrade outcome, and what the operator is told about it. */
class UpgradeOutcomeTest {

    private static CommandResult exited(int code) {
        return new CommandResult(code, "", "", false, "SHA256:x");
    }

    private static CommandResult abandoned() {
        return new CommandResult(-1, "", "", true, "SHA256:x");
    }

    @Test
    void aSucceededPullIsNoFailureAtAll() {
        assertThat(UpgradeOutcome.ofPull(exited(0))).isEmpty();
    }

    @Test
    void aNonZeroPullIsAPullFailure() {
        assertThat(UpgradeOutcome.ofPull(exited(1))).contains(UpgradeOutcome.PULL_FAILED);
    }

    @Test
    void anAbandonedPullIsATimeout_notAPullFailure() {
        assertThat(UpgradeOutcome.ofPull(abandoned())).contains(UpgradeOutcome.TIMED_OUT);
    }

    @Test
    void aSucceededRecreateIsAnUpgrade() {
        assertThat(UpgradeOutcome.ofRecreate(exited(0))).isEqualTo(UpgradeOutcome.UPGRADED);
    }

    @Test
    void aNonZeroRecreateIsARecreateFailure() {
        assertThat(UpgradeOutcome.ofRecreate(exited(1))).isEqualTo(UpgradeOutcome.RECREATE_FAILED);
    }

    @Test
    void anAbandonedRecreateIsATimeout() {
        assertThat(UpgradeOutcome.ofRecreate(abandoned())).isEqualTo(UpgradeOutcome.TIMED_OUT);
    }

    @Test
    void aFailedRecreateSaysTheOldContainerIsStillRunning_ratherThanAGenericError() {
        assertThat(UpgradeOutcome.RECREATE_FAILED.sentence("vaultwarden"))
            .contains("vaultwarden")
            .contains("still running");
    }

    @Test
    void aFailedPullSaysNothingWasChanged() {
        assertThat(UpgradeOutcome.PULL_FAILED.sentence("vaultwarden"))
            .contains("vaultwarden")
            .contains("still running");
    }

    @Test
    void everyOutcomeSaysSomethingAboutTheContainerByName() {
        for (UpgradeOutcome outcome : UpgradeOutcome.values()) {
            assertThat(outcome.sentence("vaultwarden")).contains("vaultwarden");
        }
    }

    @Test
    void onlyAnUpgradeReadsAsSuccess() {
        assertThat(UpgradeOutcome.UPGRADED.upgraded()).isTrue();
        assertThat(UpgradeOutcome.PULL_FAILED.upgraded()).isFalse();
        assertThat(UpgradeOutcome.RECREATE_FAILED.upgraded()).isFalse();
        assertThat(UpgradeOutcome.TIMED_OUT.upgraded()).isFalse();
        assertThat(UpgradeOutcome.UNREACHABLE.upgraded()).isFalse();
    }
}
