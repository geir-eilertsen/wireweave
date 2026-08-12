package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DiskFillForecastTest {

    /** 1024-blocks in a GiB. */
    private static final long GIB = 1024L * 1024L;

    /** A GiB a day, expressed as the blocks-per-hour rate the forecast carries. */
    private static final double GIB_PER_DAY = GIB / 24.0;

    /** A forecast for {@code /volume1} on the NAS, judged against 85%, with the runway under test. */
    private static DiskFillForecast.DiskFillForecastBuilder forecast() {
        return DiskFillForecast.builder()
            .machineName("nas")
            .mountPoint("/volume1")
            .currentPercent(80)
            .thresholdPercent(85)
            .fillRateKbPerHour(GIB_PER_DAY);
    }

    @Test
    void warrantsEarlyWarning_whenBelowLevelThresholdAndRunwayShort() {
        assertThat(forecast().runway(Duration.ofDays(5)).build().warrantsEarlyWarning(85, false)).isTrue();
    }

    @Test
    void warrantsEarlyWarning_false_whenRunwayBeyondHorizon() {
        assertThat(forecast().runway(Duration.ofDays(9)).build().warrantsEarlyWarning(85, false)).isFalse();
    }

    @Test
    void warrantsEarlyWarning_false_whenAtOrAboveLevelThreshold_soLevelAlertTakesOver() {
        // Short runway, but the disk is already at/over the level threshold: the pressure alert owns this,
        // the forecast must go quiet so the operator is never paged twice for the same disk.
        DiskFillForecast atThreshold =
            forecast().currentPercent(85).runway(Duration.ofDays(2)).build();
        DiskFillForecast aboveThreshold =
            forecast().currentPercent(90).runway(Duration.ofDays(1)).build();

        assertThat(atThreshold.warrantsEarlyWarning(85, false)).isTrue(); // 85 <= 85 → forecast territory
        assertThat(aboveThreshold.warrantsEarlyWarning(85, false)).isFalse();
    }

    @Test
    void aWarningAlreadySent_isNotCalledOffByAGrazeBackOverTheHorizon() {
        // A disk sitting near the line drifts either side of it — 6.96 days, 7.00, 7.04 — and a bare
        // threshold turns that drift into an all-clear and a fresh warning twice a week.
        DiskFillForecast grazing = forecast().runway(Duration.ofHours(170)).build();

        assertThat(grazing.warrantsEarlyWarning(85, false)).isFalse();  // not enough to start warning
        assertThat(grazing.warrantsEarlyWarning(85, true)).isTrue();    // but not enough to stop, either
    }

    @Test
    void aWarningAlreadySent_isCalledOffOnceTheRunwayIsGenuinelyClear() {
        assertThat(forecast().runway(Duration.ofDays(11)).build().warrantsEarlyWarning(85, true)).isFalse();
    }

    @Test
    void theHandoffGateHasNoHysteresis_theLevelAlertTakesOverAtOnce() {
        DiskFillForecast pastThreshold =
            forecast().currentPercent(90).runway(Duration.ofDays(2)).build();

        assertThat(pastThreshold.warrantsEarlyWarning(85, true)).isFalse();
    }

    @Test
    void forecastHorizon_isAWeek_notTheDayItWas() {
        // A trend measured over a week cannot say anything useful only 24 hours ahead.
        assertThat(DiskFillForecast.FORECAST_HORIZON).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void theFillRateReadsAsBytesPerDay_notPercentPerHour() {
        // The rate is blocks per hour now, because df's integer percent column cannot resolve a ~1%/day
        // fill at all. Operators read GiB/day.
        DiskFillForecast forecast =
            forecast().fillRateKbPerHour(1.2 * GIB_PER_DAY).runway(Duration.ofDays(4)).build();

        assertThat(forecast.fillRateHuman()).isEqualTo("1.2 GiB/day");
    }

    @Test
    void theRunwayReadsAsDaysOnceThereAreMoreThanTwoOfThem() {
        assertThat(forecast().runway(Duration.ofHours(30)).build().runwayHuman()).isEqualTo("30h");
        assertThat(forecast().runway(Duration.ofHours(108)).build().runwayHuman()).isEqualTo("4.5 days");
    }

    @Test
    void forecastSubject_saysItWillReachItsThreshold_notThatItWillBeFull() {
        // The runway runs to the filesystem's own threshold, so the mail must say so. "projected full"
        // would now be a lie about a disk with weeks of space left beyond the line it is about to cross.
        DiskFillForecast forecast = forecast().runway(Duration.ofHours(108)).build();

        assertThat(forecast.forecastSubject())
            .contains("nas").contains("/volume1").contains("85% threshold").contains("4.5 days")
            .doesNotContain("full");
    }

    @Test
    void forecastBody_namesTheThresholdItIsHeadingFor_theRateAndTheRunway() {
        String body = forecast().fillRateKbPerHour(1.2 * GIB_PER_DAY).runway(Duration.ofDays(4)).build()
            .forecastBody("example.com");

        assertThat(body).contains("nas").contains("80%").contains("Alert threshold: 85%")
            .contains("1.2 GiB/day").contains("4.0 days").contains("reaches 85%").contains("https://");
    }

    @Test
    void forecastBody_omitsUiLink_whenBaseDomainBlank() {
        String body = forecast().runway(Duration.ofDays(4)).build().forecastBody("  ");

        assertThat(body).doesNotContain("https://");
    }
}
