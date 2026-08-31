package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FleetCredentialStandingTest {

    private static FleetCredentialStanding standing(String name, FleetCredentialState state) {
        return new FleetCredentialStanding(TestMachineIds.of(name), name, state);
    }

    @Test
    void aCredentialHasLandedOnlyWhenAtLeastOneMachineActuallyHoldsIt() {
        assertThat(FleetCredentialStanding.anyLanded(
            List.of(standing("nas", FleetCredentialState.CURRENT)))).isTrue();
    }

    @Test
    void aPushThatReachedNobodyHasNotLanded() {
        assertThat(FleetCredentialStanding.anyLanded(List.of(
            standing("phone", FleetCredentialState.SKIPPED),
            standing("nas", FleetCredentialState.UNREACHABLE),
            standing("nuc", FleetCredentialState.FAILED)))).isFalse();
        assertThat(FleetCredentialStanding.anyLanded(List.of())).isFalse();
    }
}
