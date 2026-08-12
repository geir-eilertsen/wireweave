package net.vaier.domain;

import lombok.Builder;

import java.time.Duration;
import java.util.Locale;

/**
 * A projection of when <b>one filesystem</b> on a machine will reach <b>its own alert threshold</b>, derived
 * from the fill trend of the same {@code df} readings the disk-pressure watcher already takes. Where
 * {@link RemoteDiskUsage} answers "is the disk already too full?" (a <b>level</b>), this answers "how long
 * until it is?" (a <b>trend</b>).
 *
 * <p><b>The runway runs to the threshold, not to 100%.</b> Measured to full it was unreachable arithmetic: at
 * ~0.9%/day — the rate this was built to catch — a runway under a week means a disk already past 93%, and by
 * then {@link #warrantsEarlyWarning} has handed off to the level alert. The forecast could never fire. Running
 * to the threshold is also the more honest quantity: the operator wants warning before the disk becomes a
 * problem, not before it becomes a catastrophe, and it makes the hand-off exact — the forecast warns while
 * below the threshold, about crossing it, and the level alert takes over at the moment the forecast predicted.
 *
 * <p><b>And the rate is a byte rate, not percent-of-capacity per hour.</b> {@code df} prints an integer
 * percent, so a disk filling at ~0.9%/day reads as the same integer for a whole day and a slope fitted through
 * those integers is either zero or, on the sample straddling a tick, wildly overstated. See
 * {@link DiskFillTrend}. Emails render it per <em>day</em> ("1.2 GiB/day"), the unit an operator can picture.
 *
 * <p>Like {@link RemoteDiskUsage} it owns its own decisions and email rendering; the notification service
 * only sequences the SMTP send.
 *
 * @param machineName       the machine the forecast is for
 * @param mountPoint        the filesystem the forecast is for (e.g. {@code /volume1})
 * @param currentPercent    the most recent used percentage (0–100)
 * @param thresholdPercent  the threshold this filesystem is judged against, and the one the runway runs to
 * @param fillRateKbPerHour least-squares rate at which free space is being consumed, in 1024-blocks per hour
 * @param runway            projected time until the filesystem reaches {@code thresholdPercent}
 */
@Builder
public record DiskFillForecast(String machineName, String mountPoint, int currentPercent,
                               int thresholdPercent, double fillRateKbPerHour, Duration runway) {

    /**
     * The fixed early-warning horizon: when the {@link #runway} drops below a week the forecast warrants an
     * early warning. A week rather than the day it used to be — the trend is now measured over a week of
     * samples, and a projection that can only speak 24 hours ahead is not an early warning of anything.
     * Intentionally a constant, not a configurable setting: the disk-pressure level is the tunable knob.
     */
    public static final Duration FORECAST_HORIZON = Duration.ofDays(7);

    /**
     * How far the runway must rise before a warning already sent is called off. A disk grazing the horizon
     * drifts either side of it for a day or two — 6.96 days, 7.00, 7.04 — and a bare threshold turns that
     * drift into an all-clear followed by a fresh warning, twice a week, about a disk whose situation never
     * changed. The same reasoning as {@link RemoteDiskPressureTracker}'s bands: slipping back is not a
     * recovery.
     */
    public static final Duration EARLY_WARNING_CLEAR_HORIZON = Duration.ofDays(10);

    /**
     * Whether this forecast warrants an early warning right now, given whether one is already standing: the
     * runway is inside the horizon — the wider {@link #EARLY_WARNING_CLEAR_HORIZON} once warned, so a graze
     * back over the line is not mistaken for a recovery — AND the disk is still at or below its threshold.
     * That second clause is the hand-off gate, and it has no hysteresis on purpose: the moment the disk
     * crosses its threshold the disk-pressure alert owns it and the forecast falls silent.
     */
    public boolean warrantsEarlyWarning(int levelThreshold, boolean alreadyWarned) {
        Duration horizon = alreadyWarned ? EARLY_WARNING_CLEAR_HORIZON : FORECAST_HORIZON;
        return runway.compareTo(horizon) < 0 && currentPercent <= levelThreshold;
    }

    /** Subject line for the early-warning email. */
    public String forecastSubject() {
        return "[Vaier] " + machineName + " " + mountPoint + " projected to reach its " + thresholdPercent
            + "% threshold in ~" + runwayHuman();
    }

    /**
     * Body for the early-warning email. {@code baseDomain} builds the Vaier UI link, and when it is null
     * or blank the link is omitted.
     */
    public String forecastBody(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append("Machine: ").append(machineName).append("\n");
        body.append("Filesystem: ").append(mountPoint).append("\n");
        body.append("Used: ").append(currentPercent).append("%\n");
        body.append("Alert threshold: ").append(thresholdPercent).append("%\n");
        body.append("Filling at: ").append(fillRateHuman()).append("\n");
        body.append("Projected runway: ~").append(runwayHuman()).append(" until it reaches ")
            .append(thresholdPercent).append("%\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }

    /** The fill rate as an operator reads it — e.g. {@code 1.2 GiB/day}. */
    public String fillRateHuman() {
        return RemoteDiskUsage.humanBlocks(Math.round(fillRateKbPerHour * 24)) + "/day";
    }

    /** The runway in the coarsest unit that still says something — days once there are more than two. */
    public String runwayHuman() {
        long hours = Math.round(runway.toMinutes() / 60.0);
        if (hours < 48) {
            return hours + "h";
        }
        return String.format(Locale.ROOT, "%.1f days", hours / 24.0);
    }
}
