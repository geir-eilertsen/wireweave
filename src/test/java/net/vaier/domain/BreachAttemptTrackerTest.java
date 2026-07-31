package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BreachAttemptTrackerTest {

    private static BlockDecision decision(long id, String ip) {
        return BlockDecision.builder()
            .id(id).scenario("crowdsecurity/http-probing").sourceIp(ip).type("ban").duration("3h59m48s")
            .build();
    }

    @Test
    void reportsADecisionThatIsAlreadyActiveOnTheVeryFirstSweep() {
        // Deliberately NOT baseline-quiet, unlike the peer/disk trackers — mirrors ImageUpdateTracker: a
        // breach already underway when Vaier (re)starts is exactly the case the operator needs to hear about.
        BreachAttemptTracker tracker = new BreachAttemptTracker();

        assertThat(tracker.update(List.of(decision(1, "1.2.3.4")))).containsExactly(decision(1, "1.2.3.4"));
    }

    @Test
    void staysSilentWhileTheSameDecisionRemainsActive() {
        BreachAttemptTracker tracker = new BreachAttemptTracker();
        tracker.update(List.of(decision(1, "1.2.3.4")));

        assertThat(tracker.update(List.of(decision(1, "1.2.3.4")))).isEmpty();
    }

    @Test
    void aDecisionAbsentFromALaterSweepIsForgottenSoItIsNewAgainIfItReappears() {
        BreachAttemptTracker tracker = new BreachAttemptTracker();
        tracker.update(List.of(decision(1, "1.2.3.4")));

        tracker.update(List.of()); // the ban expired / was cleared — CrowdSec no longer reports it

        assertThat(tracker.update(List.of(decision(1, "1.2.3.4"))))
            .as("the same id coming back is news again, not a memory of the first ban")
            .containsExactly(decision(1, "1.2.3.4"));
    }

    @Test
    void reportsOnlyTheNewlyAppearedDecisionsInASweep() {
        BreachAttemptTracker tracker = new BreachAttemptTracker();
        tracker.update(List.of(decision(1, "1.2.3.4")));

        assertThat(tracker.update(List.of(decision(1, "1.2.3.4"), decision(2, "5.6.7.8"))))
            .containsExactly(decision(2, "5.6.7.8"));
    }

    @Test
    void anEmptySweepReportsNothing() {
        BreachAttemptTracker tracker = new BreachAttemptTracker();

        assertThat(tracker.update(List.of())).isEmpty();
    }
}
