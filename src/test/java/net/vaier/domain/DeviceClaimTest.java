package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceClaimTest {

    private static final Instant NOW = Instant.parse("2026-08-11T18:00:00Z");

    @Test
    void mint_producesAnOpaqueTokenAndRemembersWhenItWasClaimed() {
        DeviceClaim claim = DeviceClaim.mint(NOW);

        assertThat(claim.token()).isNotBlank().hasSizeGreaterThanOrEqualTo(32);
        assertThat(claim.claimedAt()).isEqualTo(NOW);
    }

    /** Guessable would be the whole failure: two claims must never collide. */
    @Test
    void mint_neverProducesTheSameTokenTwice() {
        assertThat(DeviceClaim.mint(NOW).token()).isNotEqualTo(DeviceClaim.mint(NOW).token());
    }

    @Test
    void matches_recognisesTheTokenItIssued() {
        DeviceClaim claim = DeviceClaim.mint(NOW);

        assertThat(claim.matches(claim.token())).isTrue();
    }

    @Test
    void matches_rejectsAnyOtherToken() {
        DeviceClaim claim = DeviceClaim.mint(NOW);

        assertThat(claim.matches(DeviceClaim.mint(NOW).token())).isFalse();
        assertThat(claim.matches("")).isFalse();
        assertThat(claim.matches(null)).isFalse();
        assertThat(claim.matches(claim.token() + "x")).isFalse();
    }

    @Test
    void aClaimWithoutATokenIsNotAClaim() {
        assertThatThrownBy(() -> new DeviceClaim("  ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeviceClaim(null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
