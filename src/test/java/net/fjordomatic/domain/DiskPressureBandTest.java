package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiskPressureBandTest {

    @Test
    void aReadingFallsIntoTheFivePointStepBelowIt() {
        assertThat(DiskPressureBand.of(80).floorPercent()).isEqualTo(80);
        assertThat(DiskPressureBand.of(84).floorPercent()).isEqualTo(80);
        assertThat(DiskPressureBand.of(85).floorPercent()).isEqualTo(85);
        assertThat(DiskPressureBand.of(89).floorPercent()).isEqualTo(85);
        assertThat(DiskPressureBand.of(90).floorPercent()).isEqualTo(90);
        assertThat(DiskPressureBand.of(95).floorPercent()).isEqualTo(95);
        assertThat(DiskPressureBand.of(99).floorPercent()).isEqualTo(95);
        assertThat(DiskPressureBand.of(100).floorPercent()).isEqualTo(100);
    }

    @Test
    void aHigherBandIsHigherThanALowerOne() {
        assertThat(DiskPressureBand.of(91).isHigherThan(DiskPressureBand.of(89))).isTrue();
        assertThat(DiskPressureBand.of(96).isHigherThan(DiskPressureBand.of(91))).isTrue();
    }

    @Test
    void aClimbWithinOneBandIsNotAHigherBand() {
        // 86 → 89 is the disk getting worse, but not by enough to be worth a second email. This is what
        // stops the escalation from becoming a re-notification timer in disguise.
        assertThat(DiskPressureBand.of(89).isHigherThan(DiskPressureBand.of(86))).isFalse();
        assertThat(DiskPressureBand.of(85).isHigherThan(DiskPressureBand.of(85))).isFalse();
    }

    @Test
    void aLowerBandIsNotHigher() {
        assertThat(DiskPressureBand.of(81).isHigherThan(DiskPressureBand.of(91))).isFalse();
    }

    @Test
    void aPercentageOutsideZeroToAHundredIsNotAReading() {
        assertThatThrownBy(() -> DiskPressureBand.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiskPressureBand.of(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBandFloorMustItselfBeAStep() {
        // Guards the persisted form: a hand-edited or older-Fjord disk-pressure.yml carrying 87 is a band
        // nothing could ever have produced, and reading it back would silently shift the escalation ladder.
        assertThatThrownBy(() -> new DiskPressureBand(87)).isInstanceOf(IllegalArgumentException.class);
    }
}
