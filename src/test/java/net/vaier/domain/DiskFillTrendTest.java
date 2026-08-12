package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

class DiskFillTrendTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
    private static final MachineId VAIER = TestMachineIds.of("vaier");

    /** 1024-blocks in a GiB — the unit {@code df -P} counts in. */
    private static final long GIB = 1024L * 1024L;
    private static final long CAPACITY = 100 * GIB;

    /** What a Docker build takes on this host, and what the nightly prune gives back. */
    private static final long BUILD = Math.round(1.2 * GIB);

    /** The global disk alert threshold — what an unconfigured filesystem is judged against. */
    private static final int LEVEL = 80;

    /** Free space lost to a build: taken at midday, returned by the prune at midnight. */
    private static long buildSpike(int hour) {
        return hour % 24 >= 12 ? BUILD : 0;
    }

    /** Free space lost to a genuine 0.9%-of-capacity-per-day fill by {@code hour}. */
    private static long filledBy(int hour) {
        return Math.round(0.9 * GIB * hour / 24.0);
    }

    /** A week of hourly readings, free space at each hour given by {@code freeAtHour}. */
    private static DiskFillTrend weekOfHourlyReadings(IntToLongFunction freeAtHour) {
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int hour = 0; hour <= 167; hour++) {
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), freeAtHour.applyAsLong(hour));
        }
        return trend;
    }

    private static RemoteDiskUsage reading(long availableKb) {
        long used = CAPACITY - availableKb;
        return new RemoteDiskUsage("vaier", "/dev/root", "/", CAPACITY, used, availableKb,
            (int) Math.round(used * 100.0 / CAPACITY));
    }

    @Test
    void aWeekOfBuildSawtoothOverAFlatDisk_raisesNoEarlyWarning() {
        // This host's disk genuinely sawtooths: ~1.2 GiB to a Docker build, given back by the nightly
        // docker-builder-prune. Underneath, nothing is filling. The old one-hour window sat *inside* one
        // build and read it as a catastrophic fill; a week of samples averages it away.
        DiskFillTrend trend = weekOfHourlyReadings(hour -> 30 * GIB - buildSpike(hour));
        // Read at the worst possible moment — the bottom of a build spike.
        RemoteDiskUsage now = reading(30 * GIB - BUILD);

        Optional<DiskFillForecast> forecast = trend.forecast(now, LEVEL);

        assertThat(trend.samples()).hasSize(168);
        assertThat(forecast.map(f -> f.warrantsEarlyWarning(LEVEL, false)).orElse(false)).isFalse();
        assertThat(forecast.map(DiskFillForecast::runway).orElse(Duration.ofDays(3650)))
            .isGreaterThan(Duration.ofDays(60));
    }

    @Test
    void theSameSawtoothOverARealFill_warnsWithARunwayInDays() {
        // 0.9% of capacity per day — the real rate the Vaier host climbed 70% → 81% over twelve days. Every
        // df reading inside a day prints the *same integer percent*, which is why fitting the percent column
        // was blind. Free space in blocks has no such floor, so the same sawtooth now rides on a trend that
        // can be seen.
        DiskFillTrend trend = weekOfHourlyReadings(hour -> 30 * GIB - filledBy(hour) - buildSpike(hour));
        RemoteDiskUsage now = reading(30 * GIB - filledBy(167) - BUILD);

        Optional<DiskFillForecast> forecast = trend.forecast(now, LEVEL);

        assertThat(forecast).isPresent();
        assertThat(forecast.get().warrantsEarlyWarning(LEVEL, false)).isTrue();
        // The runway counts down to the 80% threshold, not to 100%: at 0.9%/day the latter is 22 days out
        // and only drops under a week once the disk is past 93%, where the hand-off gate has already
        // silenced the forecast. Measured to the threshold it is days away while the disk is still in its
        // seventies.
        assertThat(forecast.get().currentPercent()).isLessThan(80);
        assertThat(forecast.get().runway()).isBetween(Duration.ofHours(1), Duration.ofDays(7));
        assertThat(forecast.get().fillRateKbPerHour() * 24 / GIB).isCloseTo(0.9, offset(0.1));
    }

    @Test
    void oneDayOfSawtooth_isNotEnoughBaselineToProjectFrom() {
        // Over a single day the build/prune cycle does not cancel out of the fit at all — it tilts the line
        // by the whole 1.2 GiB spike and reads ~1.9 GiB/day of fill on a disk that is going nowhere.
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int hour = 0; hour <= 24; hour++) {
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), 30 * GIB - buildSpike(hour));
        }

        assertThat(trend.forecast(reading(30 * GIB - BUILD), LEVEL)).isEmpty();
    }

    @Test
    void theFitIsCutToWholeDays_soATrailingBuildCannotTiltIt() {
        // Four days and eighteen hours of flat sawtooth, read mid-build. Fitting the ragged tail would drag
        // the line down by the whole spike; cut to four whole days, the disk reads as what it is — flat.
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int hour = 0; hour <= 114; hour++) {
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), 30 * GIB - buildSpike(hour));
        }

        Optional<DiskFillForecast> forecast = trend.forecast(reading(30 * GIB - BUILD), LEVEL);

        assertThat(forecast.map(f -> f.warrantsEarlyWarning(LEVEL, false)).orElse(false)).isFalse();
        assertThat(forecast.map(DiskFillForecast::runway).orElse(Duration.ofDays(3650)))
            .isGreaterThan(Duration.ofDays(60));
    }

    @Test
    void readingsInsideTheSampleInterval_areNotRetained() {
        // The watcher reads every five minutes; retaining all of them would write the store twelve times an
        // hour for no more signal than one sample buys.
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int minute = 0; minute <= 180; minute += 5) {
            trend = trend.observing(T0.plus(Duration.ofMinutes(minute)), 30 * GIB);
        }

        assertThat(trend.samples()).hasSize(4);   // t=0, +1h, +2h, +3h
    }

    @Test
    void samplesOlderThanTheRetentionWindow_areDropped() {
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int hour = 0; hour <= 240; hour++) {   // ten days of hourly readings
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), 30 * GIB);
        }

        assertThat(trend.samples()).hasSize(169);   // a week of them, inclusive of both ends
        assertThat(trend.samples().get(0).at()).isEqualTo(T0.plus(Duration.ofHours(72)));
    }

    @Test
    void tooFewSamples_projectsNothing() {
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int hour = 0; hour < 5; hour++) {
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), 30 * GIB - filledBy(hour));
        }

        assertThat(trend.forecast(reading(28 * GIB), LEVEL)).isEmpty();
    }

    @Test
    void tooShortABaseline_projectsNothing() {
        // Twelve samples in twelve hours: enough of them, but half a day of baseline cannot project days.
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");
        for (int hour = 0; hour <= 12; hour++) {
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), 30 * GIB - hour * GIB / 10);
        }

        assertThat(trend.forecast(reading(28 * GIB), LEVEL)).isEmpty();
    }

    @Test
    void aDrainingFilesystem_projectsNothing() {
        DiskFillTrend trend = weekOfHourlyReadings(hour -> 30 * GIB + filledBy(hour));

        assertThat(trend.forecast(reading(36 * GIB), LEVEL)).isEmpty();
    }

    @Test
    void aFlatFilesystem_projectsNothing() {
        DiskFillTrend trend = weekOfHourlyReadings(hour -> 30 * GIB);

        assertThat(trend.forecast(reading(30 * GIB), LEVEL)).isEmpty();
    }

    @Test
    void theWarnedLatchRidesWithTheTrend() {
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/");

        assertThat(trend.warned()).isFalse();
        assertThat(trend.withWarned(true).warned()).isTrue();
        assertThat(trend.withWarned(false)).isSameAs(trend);
        assertThat(trend.withWarned(true).observing(T0, 30 * GIB).warned()).isTrue();
    }

    @Test
    void aTrendKnowsWhichFilesystemItIsFor() {
        DiskFillTrend trend = DiskFillTrend.startFor(VAIER, "/volume1");

        assertThat(trend.isFor(VAIER, "/volume1")).isTrue();
        assertThat(trend.isFor(VAIER, "/")).isFalse();
        assertThat(trend.isFor(MachineId.generate(), "/volume1")).isFalse();
    }
}
