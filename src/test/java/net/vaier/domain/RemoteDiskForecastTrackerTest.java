package net.vaier.domain;

import net.vaier.adapter.driven.InMemoryDiskFillTrendAdapter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskForecastTrackerTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");
    /** The global disk alert threshold — what an unconfigured filesystem is judged against. */
    private static final int LEVEL = 80;

    private static final MachineId VAIER = TestMachineIds.of("vaier");
    private static final MachineId NAS = TestMachineIds.of("nas");

    /** 1024-blocks in a GiB — the unit {@code df -P} counts in. */
    private static final long GIB = 1024L * 1024L;
    private static final long CAPACITY = 100 * GIB;

    /** What a Docker build takes on this host, and what the nightly prune gives back. */
    private static final long BUILD = Math.round(1.2 * GIB);

    private final InMemoryDiskFillTrendAdapter trends = new InMemoryDiskFillTrendAdapter();

    /** Free space lost to a build: taken at midday, returned by the prune at midnight. */
    private static long buildSpike(int hour) {
        return hour % 24 >= 12 ? BUILD : 0;
    }

    /** Free space lost to a genuine 0.9%-of-capacity-per-day fill by {@code hour}. */
    private static long filledBy(int hour) {
        return Math.round(0.9 * GIB * hour / 24.0);
    }

    private static RemoteDiskUsage reading(String machineName, long availableKb) {
        return reading(machineName, "/", availableKb);
    }

    private static RemoteDiskUsage reading(String machineName, String mountPoint, long availableKb) {
        long used = CAPACITY - availableKb;
        return new RemoteDiskUsage(machineName, "/dev/root", mountPoint, CAPACITY, used, availableKb,
            (int) Math.round(used * 100.0 / CAPACITY));
    }

    /** A week of hourly readings through the tracker, keeping every observation it made. */
    private List<RemoteDiskForecastTracker.Observation> feedAWeek(RemoteDiskForecastTracker tracker,
                                                                  MachineId machineId, String machineName,
                                                                  IntToLongFunction freeAtHour) {
        List<RemoteDiskForecastTracker.Observation> observations = new ArrayList<>();
        for (int hour = 0; hour <= 167; hour++) {
            observations.add(tracker.observe(machineId, reading(machineName, freeAtHour.applyAsLong(hour)),
                T0.plus(Duration.ofHours(hour)), LEVEL));
        }
        return observations;
    }

    private static List<DiskFillForecast> earlyWarnings(List<RemoteDiskForecastTracker.Observation> obs) {
        return obs.stream().flatMap(o -> o.earlyWarning().stream()).toList();
    }

    @Test
    void aWeekOfBuildSawtoothOverAFlatDisk_neverWarns() {
        // The disk this runs on gains and loses ~1.2 GiB every day to a Docker build and the nightly prune,
        // over an underlying trend that is going nowhere. Any window short enough to sit inside one build
        // reads that build as a catastrophic fill — which is exactly what the old one-hour window did.
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);

        List<RemoteDiskForecastTracker.Observation> week =
            feedAWeek(tracker, VAIER, "vaier", hour -> 30 * GIB - buildSpike(hour));

        assertThat(earlyWarnings(week)).isEmpty();
    }

    @Test
    void theSameSawtoothOverARealFill_warnsOnceWithARunwayInDays() {
        // Same sawtooth, now riding on the real thing: 0.9% of capacity a day, the rate this host actually
        // climbed 70% → 81% over twelve days. The percent column cannot see it at all; free space can.
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);

        List<RemoteDiskForecastTracker.Observation> week = feedAWeek(tracker, VAIER, "vaier",
            hour -> 30 * GIB - filledBy(hour) - buildSpike(hour));

        List<DiskFillForecast> warnings = earlyWarnings(week);
        assertThat(warnings).hasSize(1);
        // Days, not the ~19 hours the percent fit used to invent for a disk with weeks left — and the daily
        // build spike must not flap the gate into an all-clear and back either.
        assertThat(warnings.get(0).runway()).isBetween(Duration.ofDays(5), Duration.ofDays(7));
        assertThat(week.stream().flatMap(o -> o.cleared().stream())).isEmpty();
    }

    @Test
    void aDiskInItsSeventies_isWarnedAboutAWeekBeforeItCrossesEighty() {
        // The case the whole feature exists for, and the one it could not do when the runway ran to 100%:
        // at 0.9%/day a runway under a week meant a disk already past 93%, where the hand-off gate has
        // already given it to the level alert. Measured to the threshold instead, the warning lands while
        // there is still something to do about it.
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);
        IntToLongFunction free = hour -> 30 * GIB - filledBy(hour) - buildSpike(hour);

        DiskFillForecast warning = null;
        int warnedAtHour = 0;
        int crossedEightyAtHour = 0;
        for (int hour = 0; hour <= 12 * 24; hour++) {
            RemoteDiskUsage now = reading("vaier", free.applyAsLong(hour));
            RemoteDiskForecastTracker.Observation obs =
                tracker.observe(VAIER, now, T0.plus(Duration.ofHours(hour)), LEVEL);
            if (warning == null && obs.earlyWarning().isPresent()) {
                warning = obs.earlyWarning().get();
                warnedAtHour = hour;
            }
            if (crossedEightyAtHour == 0 && now.isAbove(LEVEL)) {
                crossedEightyAtHour = hour;
            }
        }

        assertThat(warning).isNotNull();
        assertThat(warning.currentPercent()).isBetween(71, 77);   // not the 94% it used to need
        assertThat(warning.thresholdPercent()).isEqualTo(LEVEL);
        assertThat(warning.runway()).isBetween(Duration.ofDays(5), Duration.ofDays(7));
        assertThat(crossedEightyAtHour - warnedAtHour).isGreaterThan(7 * 24);
    }

    @Test
    void aRedeployKeepsBothTheTrendAndTheWarning() {
        // The bug this whole change is about: the tracker was a field on the scheduled watcher, so every
        // redeploy — several a day here — threw away the samples *and* the already-warned latch. A week of
        // baseline can never accumulate that way, and a disk admins were already told about would be told
        // about again.
        RemoteDiskForecastTracker before = new RemoteDiskForecastTracker(trends);
        feedAWeek(before, VAIER, "vaier", hour -> 30 * GIB - filledBy(hour) - buildSpike(hour));

        RemoteDiskForecastTracker afterRedeploy = new RemoteDiskForecastTracker(trends);
        RemoteDiskForecastTracker.Observation next = afterRedeploy.observe(VAIER,
            reading("vaier", 30 * GIB - filledBy(168) - buildSpike(168)),
            T0.plus(Duration.ofHours(168)), LEVEL);

        assertThat(next.transition()).isEqualTo(RemoteDiskForecastTracker.Transition.NONE);
        assertThat(next.earlyWarning()).isEmpty();
        assertThat(trends.find(VAIER, "/").orElseThrow().samples()).hasSizeGreaterThan(160);
    }

    @Test
    void handoff_climbingPastTheLevelThreshold_suppressesTheAllClear() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);
        feedAWeek(tracker, VAIER, "vaier", hour -> 30 * GIB - filledBy(hour) - buildSpike(hour));

        // It keeps filling and crosses its threshold: the disk-pressure alert owns it now, so raising a
        // forecast all-clear at the same poll would contradict it.
        RemoteDiskForecastTracker.Observation obs = tracker.observe(VAIER, reading("vaier", 2 * GIB),
            T0.plus(Duration.ofHours(168)), LEVEL);

        assertThat(obs.transition()).isEqualTo(RemoteDiskForecastTracker.Transition.CROSSED_BELOW);
        assertThat(obs.cleared()).isEmpty();
    }

    @Test
    void genuineRecovery_spaceFreedBelowTheThreshold_emitsTheAllClear() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);
        feedAWeek(tracker, VAIER, "vaier", hour -> 30 * GIB - filledBy(hour) - buildSpike(hour));

        // Someone freed 40 GiB. The trend is no longer heading anywhere and the disk is well below its
        // threshold, so this is a recovery worth an all-clear.
        RemoteDiskForecastTracker.Observation obs = null;
        for (int hour = 168; hour <= 200; hour++) {
            obs = tracker.observe(VAIER, reading("vaier", 45 * GIB), T0.plus(Duration.ofHours(hour)), LEVEL);
            if (obs.cleared().isPresent()) break;
        }

        assertThat(obs.transition()).isEqualTo(RemoteDiskForecastTracker.Transition.CROSSED_BELOW);
        assertThat(obs.cleared()).isPresent();
        assertThat(obs.cleared().get().machineName()).isEqualTo("vaier");
        assertThat(obs.cleared().get().currentPercent()).isEqualTo(55);
    }

    @Test
    void twoMachinesSharingAName_areStillTwoMachines() {
        // lan-servers.yml really does hold two machines both called "Printer". Keyed on the name they shared
        // one trend and were fitted through each other, and a rename discarded the lot.
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);
        MachineId printerOne = MachineId.generate();
        MachineId printerTwo = MachineId.generate();

        feedAWeek(tracker, printerOne, "Printer", hour -> 30 * GIB - filledBy(hour) - buildSpike(hour));
        RemoteDiskForecastTracker.Observation obs = tracker.observe(printerTwo, reading("Printer", 30 * GIB),
            T0.plus(Duration.ofHours(167)), LEVEL);

        assertThat(obs.transition()).isEqualTo(RemoteDiskForecastTracker.Transition.NONE);
        assertThat(obs.earlyWarning()).isEmpty();
    }

    @Test
    void twoFilesystemsOnOneMachine_areTrendedApart() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);

        for (int hour = 0; hour <= 167; hour++) {
            Instant at = T0.plus(Duration.ofHours(hour));
            tracker.observe(NAS, reading("nas", "/", 30 * GIB), at, LEVEL);
            tracker.observe(NAS, reading("nas", "/volume1", 30 * GIB - filledBy(hour)), at, LEVEL);
        }

        assertThat(trends.find(NAS, "/").orElseThrow().warned()).isFalse();
        assertThat(trends.find(NAS, "/volume1").orElseThrow().warned()).isTrue();
    }

    @Test
    void aFiveMinuteSweep_writesTheStoreAboutOncePerHour() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);

        for (int minute = 0; minute <= 180; minute += 5) {
            tracker.observe(VAIER, reading("vaier", 30 * GIB), T0.plus(Duration.ofMinutes(minute)), LEVEL);
        }

        assertThat(trends.saves()).isEqualTo(4);   // t=0, +1h, +2h, +3h — not 37
    }

    @Test
    void aDeletedMachinesTrend_isNotKeptForever() {
        RemoteDiskForecastTracker tracker = new RemoteDiskForecastTracker(trends);
        tracker.observe(VAIER, reading("vaier", 30 * GIB), T0, LEVEL);
        tracker.observe(NAS, reading("nas", 30 * GIB), T0, LEVEL);

        trends.retainOnly(Set.of(VAIER));

        assertThat(trends.find(NAS, "/")).isEmpty();
        assertThat(trends.find(VAIER, "/")).isPresent();
    }
}
