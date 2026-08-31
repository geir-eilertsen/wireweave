package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FleetCredentialStateTest {

    @Test
    void onlyAMissingOrStaleCredentialIsSomethingTheBackgroundSweepMayHeal() {
        assertThat(FleetCredentialState.MISSING.needsHealing()).isTrue();
        assertThat(FleetCredentialState.STALE.needsHealing()).isTrue();

        assertThat(FleetCredentialState.CURRENT.needsHealing()).isFalse();
        // A machine Vaier is not allowed to reach is not a hole to fill.
        assertThat(FleetCredentialState.SKIPPED.needsHealing()).isFalse();
        assertThat(FleetCredentialState.UNREACHABLE.needsHealing()).isFalse();
        // A withdrawn credential must never be pushed back by the healer.
        assertThat(FleetCredentialState.WITHDRAWN.needsHealing()).isFalse();
        // A write that already failed is not retried into a loop by the sweep's own reading.
        assertThat(FleetCredentialState.FAILED.needsHealing()).isFalse();
    }
}
