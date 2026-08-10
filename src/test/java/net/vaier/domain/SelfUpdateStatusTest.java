package net.vaier.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Reading back the account the update script left on the host, after the process that started it is gone. */
class SelfUpdateStatusTest {

    @Test
    void anUpdateThatWorkedReadsAsSuccess_andCarriesWhatItCameFrom() {
        SelfUpdateStatus s = SelfUpdateStatus.parse(
            "run-7 UPGRADED 2026-07-23T10:04:11Z getvaier/vaier@sha256:abc");

        assertThat(s.outcome()).isEqualTo(SelfUpdateStatus.Outcome.UPGRADED);
        assertThat(s.runId()).isEqualTo("run-7");
        assertThat(s.detail()).isEqualTo("getvaier/vaier@sha256:abc");
        assertThat(s.trouble()).as("a working update is not news").isFalse();
    }

    @Test
    void aRollbackIsTrouble_becauseTheNewBuildDidNotComeUp() {
        // Vaier is running again, so nothing looks broken — which is exactly why this has to be said out
        // loud. Silence here would mean an update quietly reverting every time and nobody ever knowing.
        SelfUpdateStatus s = SelfUpdateStatus.parse(
            "run-8 ROLLED_BACK 2026-07-23T10:09:02Z getvaier/vaier@sha256:old");

        assertThat(s.outcome()).isEqualTo(SelfUpdateStatus.Outcome.ROLLED_BACK);
        assertThat(s.trouble()).isTrue();
    }

    @Test
    void aFailureIsTrouble_andSaysWhichStepFell() {
        SelfUpdateStatus s = SelfUpdateStatus.parse("run-9 FAILED 2026-07-23T10:00:00Z pull-failed");

        assertThat(s.outcome()).isEqualTo(SelfUpdateStatus.Outcome.FAILED);
        assertThat(s.trouble()).isTrue();
        assertThat(s.detail()).isEqualTo("pull-failed");
    }

    @Test
    void noFileMeansNothingHasEverBeenUpdated_notAFailure() {
        // A host that has never updated has no result file, and `cat` says so on stderr with empty stdout.
        // Reading that as a failed update would put a permanent red mark on a healthy install.
        assertThat(SelfUpdateStatus.parse(null).outcome()).isEqualTo(SelfUpdateStatus.Outcome.NONE);
        assertThat(SelfUpdateStatus.parse("   ").outcome()).isEqualTo(SelfUpdateStatus.Outcome.NONE);
        assertThat(SelfUpdateStatus.parse(null).trouble()).isFalse();
    }

    @Test
    void anUnreadableLineIsUnknown_ratherThanAGuess() {
        assertThat(SelfUpdateStatus.parse("something else entirely").outcome())
            .isEqualTo(SelfUpdateStatus.Outcome.UNKNOWN);
    }

    @Test
    void theComposeProjectIsReadOffTheContainer_notConfigured() {
        // Where the compose file lives is a fact about the running container, and asking the operator to
        // type it into an env var would be asking them for something Vaier can see. Docker stamps both the
        // working directory and the service name onto every container compose starts.
        String cmd = SelfUpdateScript.inspectComposeLabels("abc123");
        assertThat(cmd).contains("com.docker.compose.project.working_dir");
        assertThat(cmd).contains("com.docker.compose.service");
        assertThat(cmd).contains("'abc123'");

        SelfUpdateScript.ComposeLocation at =
            SelfUpdateScript.parseComposeLabels("/home/ubuntu/vaier\tvaier\n").orElseThrow();
        assertThat(at.workingDir()).isEqualTo("/home/ubuntu/vaier");
        assertThat(at.service()).isEqualTo("vaier");
    }

    @Test
    void aContainerComposeDidNotStart_hasNoProjectToUpdateIn() {
        // No labels means this container was started by hand, not by compose — there is no `docker compose
        // up` that would bring it back, so an update would take Vaier down and leave it down.
        assertThat(SelfUpdateScript.parseComposeLabels("\t\n")).isEmpty();
        assertThat(SelfUpdateScript.parseComposeLabels("")).isEmpty();
        assertThat(SelfUpdateScript.parseComposeLabels(null)).isEmpty();
    }
}
