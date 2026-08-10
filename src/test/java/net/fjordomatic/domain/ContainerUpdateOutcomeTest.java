package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a finished command run reads as an update outcome, and what the operator is told about it. */
class ContainerUpdateOutcomeTest {

    private static CommandResult exited(int code) {
        return new CommandResult(code, "", "", false, "SHA256:x");
    }

    private static CommandResult abandoned() {
        return new CommandResult(-1, "", "", true, "SHA256:x");
    }

    @Test
    void aSucceededPullIsNoFailureAtAll() {
        assertThat(ContainerUpdateOutcome.ofPull(exited(0))).isEmpty();
    }

    @Test
    void aNonZeroPullIsAPullFailure() {
        assertThat(ContainerUpdateOutcome.ofPull(exited(1))).contains(ContainerUpdateOutcome.PULL_FAILED);
    }

    @Test
    void anAbandonedPullIsATimeout_notAPullFailure() {
        assertThat(ContainerUpdateOutcome.ofPull(abandoned())).contains(ContainerUpdateOutcome.TIMED_OUT);
    }

    @Test
    void aSucceededRecreateIsAnUpdate() {
        assertThat(ContainerUpdateOutcome.ofRecreate(exited(0))).isEqualTo(ContainerUpdateOutcome.UPDATED);
    }

    @Test
    void aNonZeroRecreateIsARecreateFailure() {
        assertThat(ContainerUpdateOutcome.ofRecreate(exited(1))).isEqualTo(ContainerUpdateOutcome.RECREATE_FAILED);
    }

    @Test
    void anAbandonedRecreateIsATimeout() {
        assertThat(ContainerUpdateOutcome.ofRecreate(abandoned())).isEqualTo(ContainerUpdateOutcome.TIMED_OUT);
    }

    @Test
    void aFailedRecreateSaysTheOldContainerIsStillRunning_ratherThanAGenericError() {
        assertThat(ContainerUpdateOutcome.RECREATE_FAILED.sentence("vaultwarden"))
            .contains("vaultwarden")
            .contains("still running");
    }

    @Test
    void aFailedPullSaysNothingWasChanged() {
        assertThat(ContainerUpdateOutcome.PULL_FAILED.sentence("vaultwarden"))
            .contains("vaultwarden")
            .contains("still running");
    }

    @Test
    void everyOutcomeSaysSomethingAboutTheContainerByName() {
        for (ContainerUpdateOutcome outcome : ContainerUpdateOutcome.values()) {
            assertThat(outcome.sentence("vaultwarden")).contains("vaultwarden");
        }
    }

    @Test
    void onlyAnUpdateReadsAsSuccess() {
        assertThat(ContainerUpdateOutcome.UPDATED.updated()).isTrue();
        assertThat(ContainerUpdateOutcome.PULL_FAILED.updated()).isFalse();
        assertThat(ContainerUpdateOutcome.RECREATE_FAILED.updated()).isFalse();
        assertThat(ContainerUpdateOutcome.TIMED_OUT.updated()).isFalse();
        assertThat(ContainerUpdateOutcome.UNREACHABLE.updated()).isFalse();
    }
}
