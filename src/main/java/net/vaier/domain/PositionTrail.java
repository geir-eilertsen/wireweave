package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Where one machine has actually been: the {@link ReportedPosition}s it sent that were worth keeping,
 * oldest first. Every decision the trail rests on lives here — what earns a place in it, how far back it
 * goes, and how many points it will ever hold.
 *
 * <p><b>Reported positions only, by type.</b> An {@link PlacementSource#ISP_ESTIMATE ISP estimate} can
 * never join a trail: a whole carrier block resolves to the carrier's head office, so a trail of estimates
 * is a line from that office to itself however far the device travelled. That is the dishonesty the
 * reported position exists to remove, and drawing it as a journey would put it back with a longer line.
 *
 * <p>Bounded twice on purpose. {@link #RETENTION} is the bound that matters to a person — how long Vaier
 * remembers where they went. {@link #MAX_POINTS} is the bound that matters to the file: a device sharing
 * continuously while travelling reports far faster than the retention window alone would ever prune.
 */
public record PositionTrail(List<ReportedPosition> points) {

    /**
     * How far back a trail goes. A month is a holiday and the journey home — long enough to be worth
     * drawing, short enough that a trail is a recent record rather than a standing history of somebody's
     * movements. Deliberately its own constant: other things in Vaier are also kept for 30 days, and they
     * are different questions that happen to share an answer today.
     */
    public static final Duration RETENTION = Duration.ofDays(30);

    /**
     * The most points one machine's trail will ever hold, oldest dropped first. {@link #RETENTION} bounds
     * a trail in time but not in size — at {@link #MIN_MOVE_METRES} granularity a single day's driving can
     * produce thousands of points — and this trail is written to a file and sent whole with every peer
     * list. Five hundred is a polyline that still reads as a journey and a payload measured in tens of
     * kilobytes; past that the operator is looking at a smear, not a route.
     */
    public static final int MAX_POINTS = 500;

    /** Long enough that a device sitting still adds a handful of points a day, not one per page load. */
    static final Duration MIN_INTERVAL = Duration.ofMinutes(10);

    /**
     * Below this a device has not been anywhere: it is GPS drift, a different room, or a browser
     * re-reading the same fix. Comfortably above the accuracy a phone reports in the open.
     */
    static final double MIN_MOVE_METRES = 100;

    /** Mean Earth radius, which is the accuracy this measurement is aiming for and no more. */
    private static final double EARTH_RADIUS_METRES = 6_371_000;

    public PositionTrail {
        points = points == null ? List.of() : List.copyOf(capped(points));
    }

    /** A machine that has never reported, or one whose trail was just forgotten. */
    public static PositionTrail empty() {
        return new PositionTrail(List.of());
    }

    /**
     * This trail after a fresh report: the point joins it only when it {@link #earnsAPlace earns one},
     * and whatever has aged out or overflowed goes.
     *
     * <p>A report that does not earn a place is not lost — it is still the machine's current
     * {@link ReportedPosition}, which is what says where the device is <em>now</em>. The trail answers a
     * different question, and answering it point-for-point would make the file grow with page loads.
     */
    public PositionTrail extendedWith(ReportedPosition point) {
        if (point == null || !earnsAPlace(point)) return this;
        List<ReportedPosition> kept = new ArrayList<>(points);
        kept.add(point);
        return new PositionTrail(capped(within(kept, point.reportedAt())));
    }

    /** This trail as it should be shown at {@code now} — retention holds for a device that stopped too. */
    public PositionTrail retained(Instant now) {
        List<ReportedPosition> kept = within(points, now);
        return kept.size() == points.size() ? this : new PositionTrail(kept);
    }

    /**
     * Whether a report is meaningfully new: far enough from the last retained point, or long enough after
     * it. Either alone is enough — a device that moves says so by moving, and one that stays put still
     * earns the occasional point so a trail records that it was somewhere for a while.
     */
    private boolean earnsAPlace(ReportedPosition point) {
        if (points.isEmpty()) return true;
        ReportedPosition last = points.get(points.size() - 1);
        return Duration.between(last.reportedAt(), point.reportedAt()).compareTo(MIN_INTERVAL) >= 0
            || metresBetween(last, point) > MIN_MOVE_METRES;
    }

    /**
     * Equirectangular approximation — the two points are metres to kilometres apart, where flattening the
     * sphere costs far less than the accuracy of the fix itself, and haversine's trigonometry buys nothing
     * a threshold comparison can see. Longitude is scaled by the cosine of the latitude: at 63°N a degree
     * of longitude is under half a degree of latitude, so skipping that reads a stationary device as moving.
     */
    private static double metresBetween(ReportedPosition from, ReportedPosition to) {
        double meanLatitude = Math.toRadians((from.latitude() + to.latitude()) / 2);
        double x = Math.toRadians(to.longitude() - from.longitude()) * Math.cos(meanLatitude);
        double y = Math.toRadians(to.latitude() - from.latitude());
        return EARTH_RADIUS_METRES * Math.hypot(x, y);
    }

    /** Only what is still inside {@link #RETENTION} at {@code now}. */
    private static List<ReportedPosition> within(List<ReportedPosition> points, Instant now) {
        Instant oldestKept = now.minus(RETENTION);
        return points.stream().filter(point -> !point.reportedAt().isBefore(oldestKept)).toList();
    }

    /**
     * At most {@link #MAX_POINTS}, keeping the most recent. Enforced in the constructor, so it holds for a
     * trail read back off disk as much as for one grown a point at a time — otherwise a file that somehow
     * held more (a hand edit, a bug, an older format) would ride every peer listing at full length until
     * some later report happened to trim it.
     */
    private static List<ReportedPosition> capped(List<ReportedPosition> points) {
        return points.size() <= MAX_POINTS ? points
            : points.subList(points.size() - MAX_POINTS, points.size());
    }
}
