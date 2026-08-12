package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The retained fill history of <b>one filesystem</b>, and whether admins have already been warned that it is
 * heading for its alert threshold. Every decision the disk-fill forecast rests on lives here: what is worth
 * retaining, how long, how much baseline a projection needs, and the projection itself.
 *
 * <p><b>Bytes, not percent.</b> The forecast used to fit its line through {@code df}'s integer {@code Use%}
 * column over a one-hour window. The Vaier host climbed 70% → 81% over twelve days — about 0.9%/day — so
 * every reading in any such window was the <em>same integer</em>, the slope was exactly zero, and no forecast
 * could ever be produced. In the rare window straddling an integer tick it read ~1.09%/h instead: a 19-hour
 * runway for a disk with 23 days left. Blind almost always, ~29× wrong otherwise. {@link DiskFillSample}
 * carries {@code availableKb}, which has no such floor.
 *
 * <p><b>And a week of hourly samples, fitted over whole days.</b> This host's disk sawtooths: a Docker build
 * takes ~1.2 GiB and the nightly prune gives it back. That cycle only cancels out of a least-squares slope
 * across complete days, so both {@link #MIN_SPAN} and the fit window are cut to them.
 */
public record DiskFillTrend(MachineId machineId, String mountPoint, List<DiskFillSample> samples,
                            boolean warned) {

    /** How far back samples are kept. Long enough that a daily build/prune cycle averages out. */
    public static final Duration RETENTION_WINDOW = Duration.ofDays(7);

    /** At most one retained sample per hour, however often the watcher actually reads the disk. */
    public static final Duration SAMPLE_INTERVAL = Duration.ofHours(1);

    /** Below this many retained samples there is not enough to fit a line worth acting on. */
    static final int MIN_SAMPLES = 12;

    /**
     * A projection over days needs a baseline measured in days. Three of them, not one: the build/prune
     * cycle only cancels out of the fit across whole days, and its leftover bias falls off as the square of
     * how many the window holds. Over a single day it reads as ~1.9 GiB/day of fill on a disk going nowhere.
     */
    static final Duration MIN_SPAN = Duration.ofDays(3);

    /** The period the build/prune cycle repeats on, and so the granularity the fit window is cut to. */
    private static final Duration CYCLE = Duration.ofDays(1);

    /** A century — past it a disk is not filling in any sense that matters, and it keeps the maths finite. */
    private static final double MAX_RUNWAY_HOURS = 24 * 365 * 100.0;

    public DiskFillTrend {
        if (machineId == null) {
            throw new IllegalArgumentException("A disk-fill trend needs a machine id");
        }
        if (mountPoint == null || mountPoint.isBlank()) {
            throw new IllegalArgumentException("A disk-fill trend needs a mount point");
        }
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /** A filesystem Vaier has never sampled: no history, and nothing said about it yet. */
    public static DiskFillTrend startFor(MachineId machineId, String mountPoint) {
        return new DiskFillTrend(machineId, mountPoint, List.of(), false);
    }

    /** Whether this trend is the one for {@code mountPoint} on {@code machineId}. */
    public boolean isFor(MachineId otherMachineId, String otherMountPoint) {
        return machineId.isSameAs(otherMachineId) && mountPoint.equals(otherMountPoint);
    }

    /**
     * This trend after a reading of {@code availableKb} at {@code at}: retained when the newest sample is at
     * least {@link #SAMPLE_INTERVAL} old, and whatever has aged out of {@link #RETENTION_WINDOW} dropped.
     * Unchanged when the reading is not yet due — which is what keeps the watcher's five-minute rounds from
     * writing the store twelve times an hour.
     */
    public DiskFillTrend observing(Instant at, long availableKb) {
        if (!samples.isEmpty()) {
            Instant newest = samples.get(samples.size() - 1).at();
            if (Duration.between(newest, at).compareTo(SAMPLE_INTERVAL) < 0) {
                return this;
            }
        }
        List<DiskFillSample> retained = new ArrayList<>(samples);
        retained.add(new DiskFillSample(at, availableKb));
        Instant oldestKept = at.minus(RETENTION_WINDOW);
        retained.removeIf(sample -> sample.at().isBefore(oldestKept));
        return new DiskFillTrend(machineId, mountPoint, retained, warned);
    }

    /** This trend with the already-warned latch set, or itself when it is already there. */
    public DiskFillTrend withWarned(boolean nowWarned) {
        return nowWarned == warned ? this
            : new DiskFillTrend(machineId, mountPoint, samples, nowWarned);
    }

    /**
     * Project when {@code filesystem} reaches {@code levelThreshold} — the threshold it is judged against,
     * already resolved by the caller — or empty when there is nothing worth warning about: too few retained
     * samples, too short a baseline, or free space that is flat or growing (a filesystem that is not filling
     * has no finite runway).
     */
    public Optional<DiskFillForecast> forecast(RemoteDiskUsage filesystem, int levelThreshold) {
        if (samples.size() < MIN_SAMPLES) {
            return Optional.empty();
        }
        Instant last = samples.get(samples.size() - 1).at();
        if (Duration.between(samples.get(0).at(), last).compareTo(MIN_SPAN) < 0) {
            return Optional.empty();
        }

        // Fit over a whole number of days. A daily build that takes 1.2 GiB and gives it back cancels out of
        // the slope only across complete cycles; a window ending mid-build tilts the line by the whole spike
        // and reads an afternoon's build as a disk about to fill.
        List<DiskFillSample> window = wholeCyclesEndingAt(last);
        if (window.size() < MIN_SAMPLES) {
            return Optional.empty();
        }
        Instant first = window.get(0).at();

        // Least squares of free space (y, in 1024-blocks) against hours since the first sample. Origin at
        // `first` keeps the x values small; the fit is invariant to that choice.
        double n = window.size();
        double sumX = 0;
        double sumY = 0;
        double sumXX = 0;
        double sumXY = 0;
        for (DiskFillSample sample : window) {
            double x = hoursBetween(first, sample.at());
            double y = sample.availableKb();
            sumX += x;
            sumY += y;
            sumXX += x * x;
            sumXY += x * y;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (denominator == 0) {
            return Optional.empty();
        }
        double slopeKbPerHour = (n * sumXY - sumX * sumY) / denominator;
        // Free space falling is the filesystem filling, so the rate of interest is the negated slope.
        double fillRateKbPerHour = -slopeKbPerHour;
        if (fillRateKbPerHour <= 0) {
            return Optional.empty();
        }

        // The runway uses the *fitted* free space, not the live reading. A build spike drops free space
        // 1.2 GiB for an afternoon, and dividing that dip by the rate would flip the gate on and off with
        // every build — an all-clear email each evening and a fresh warning each morning.
        double fittedAvailableKb =
            Math.max(0, (sumY + slopeKbPerHour * (n * hoursBetween(first, last) - sumX)) / n);
        // ...and it counts down to the filesystem's own threshold, not to 100%. To 100% it was unreachable:
        // at 0.9%/day a runway under a week meant a disk already past 93%, where warrantsEarlyWarning has
        // handed off to the level alert, so the warning could never be sent.
        double headroomKb = fittedAvailableKb - filesystem.availableKbAt(levelThreshold);
        if (headroomKb <= 0) {
            // Already at its threshold on the trend: no runway left to report, and the level alert owns it.
            // Reporting a zero runway instead would re-warn every time the nightly prune dipped the disk
            // back under the threshold for a few hours.
            return Optional.empty();
        }
        double runwayHours = headroomKb / fillRateKbPerHour;
        Duration runway = Duration.ofSeconds(Math.round(Math.min(runwayHours, MAX_RUNWAY_HOURS) * 3600.0));
        return Optional.of(DiskFillForecast.builder()
            .machineName(filesystem.machineName())
            .mountPoint(mountPoint)
            .currentPercent(filesystem.usedPercent())
            .thresholdPercent(levelThreshold)
            .fillRateKbPerHour(fillRateKbPerHour)
            .runway(runway)
            .build());
    }

    /** The newest whole number of {@link #CYCLE}s of samples, ending at {@code last}. */
    private List<DiskFillSample> wholeCyclesEndingAt(Instant last) {
        long span = Duration.between(samples.get(0).at(), last).toSeconds();
        long cycles = span / CYCLE.toSeconds();
        Instant from = last.minus(CYCLE.multipliedBy(cycles));
        return samples.stream().filter(sample -> !sample.at().isBefore(from)).toList();
    }

    private static double hoursBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 3_600_000.0;
    }
}
