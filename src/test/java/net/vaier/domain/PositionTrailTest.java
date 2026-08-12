package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PositionTrailTest {

    private static final Instant NOON = Instant.parse("2026-08-11T12:00:00Z");
    private static final double LAT = 63.4305;
    private static final double LON = 10.3951;

    private static ReportedPosition at(double latitude, double longitude, Instant when) {
        return ReportedPosition.report(latitude, longitude, 12.0, when);
    }

    private static PositionTrail trailFrom(ReportedPosition... points) {
        PositionTrail trail = PositionTrail.empty();
        for (ReportedPosition point : points) trail = trail.extendedWith(point);
        return trail;
    }

    @Test
    void empty_holdsNoPoints() {
        assertThat(PositionTrail.empty().points()).isEmpty();
    }

    @Test
    void extendedWith_keepsTheFirstPointItIsEverGiven() {
        PositionTrail trail = PositionTrail.empty().extendedWith(at(LAT, LON, NOON));

        assertThat(trail.points()).singleElement().satisfies(point -> {
            assertThat(point.latitude()).isEqualTo(LAT);
            assertThat(point.reportedAt()).isEqualTo(NOON);
        });
    }

    // --- what earns a place: meaningfully later, or meaningfully elsewhere ---

    /** The flood this rule exists to stop: a browser sharing continuously from a desk. */
    @Test
    void extendedWith_dropsAReportFromTheSamePlaceMomentsLater() {
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT, LON, NOON.plus(Duration.ofMinutes(2))));

        assertThat(trail.points()).singleElement()
            .satisfies(point -> assertThat(point.reportedAt()).isEqualTo(NOON));
    }

    @Test
    void extendedWith_keepsAPointTheDeviceHasClearlyMovedAwayFrom() {
        // ~330 m north, two minutes on — a moving device, not a resting one.
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT + 0.003, LON, NOON.plus(Duration.ofMinutes(2))));

        assertThat(trail.points()).hasSize(2);
    }

    @Test
    void extendedWith_keepsAPointOnceEnoughTimeHasPassedEvenWithoutMoving() {
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT, LON, NOON.plus(Duration.ofMinutes(11))));

        assertThat(trail.points()).hasSize(2);
    }

    /**
     * Longitudes narrow toward the pole, and the fleet lives at 63°N where a degree of longitude is
     * less than half a degree of latitude. Measured in raw degrees this step reads ~165 m and would be
     * kept; it is really ~75 m, and a device that has not moved must not draw a trail.
     */
    @Test
    void extendedWith_measuresDistanceWithLongitudesNarrowingTowardThePole() {
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT, LON + 0.0015, NOON.plus(Duration.ofMinutes(2))));

        assertThat(trail.points()).hasSize(1);
    }

    @Test
    void extendedWith_holdsPointsOldestFirst() {
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT, LON, NOON.plus(Duration.ofMinutes(11))),
            at(LAT, LON, NOON.plus(Duration.ofMinutes(22))));

        assertThat(trail.points()).extracting(ReportedPosition::reportedAt)
            .containsExactly(NOON, NOON.plus(Duration.ofMinutes(11)), NOON.plus(Duration.ofMinutes(22)));
    }

    // --- the two bounds: how long, and how many ---

    @Test
    void extendedWith_forgetsPointsOlderThanRetention() {
        Instant later = NOON.plus(PositionTrail.RETENTION).plus(Duration.ofHours(1));

        PositionTrail trail = trailFrom(at(LAT, LON, NOON), at(LAT, LON, later));

        assertThat(trail.points()).singleElement()
            .satisfies(point -> assertThat(point.reportedAt()).isEqualTo(later));
    }

    @Test
    void extendedWith_neverGrowsPastTheHardCap() {
        PositionTrail trail = PositionTrail.empty();
        int overflow = 50;
        for (int i = 0; i < PositionTrail.MAX_POINTS + overflow; i++) {
            trail = trail.extendedWith(at(LAT, LON, NOON.plus(Duration.ofMinutes(11L * i))));
        }

        assertThat(trail.points()).hasSize(PositionTrail.MAX_POINTS);
        // The most recent survive: a capped trail loses where the device was, never where it is.
        assertThat(trail.points().get(trail.points().size() - 1).reportedAt())
            .isEqualTo(NOON.plus(Duration.ofMinutes(11L * (PositionTrail.MAX_POINTS + overflow - 1))));
        assertThat(trail.points().get(0).reportedAt())
            .isEqualTo(NOON.plus(Duration.ofMinutes(11L * overflow)));
    }

    /**
     * The cap is an invariant of the type, not merely of the write path. A trail restored from a file that
     * somehow holds more — a hand edit, an older format, a bug — would otherwise ride every peer listing at
     * full length until the next accepted report happened to trim it.
     */
    @Test
    void aTrailReadBackAtMoreThanTheCapIsCappedOnTheSpot() {
        List<ReportedPosition> overflowing = new ArrayList<>();
        for (int i = 0; i < PositionTrail.MAX_POINTS + 40; i++) {
            overflowing.add(at(LAT, LON, NOON.plus(Duration.ofMinutes(11L * i))));
        }

        PositionTrail trail = new PositionTrail(overflowing);

        assertThat(trail.points()).hasSize(PositionTrail.MAX_POINTS);
        assertThat(trail.points().get(trail.points().size() - 1).reportedAt())
            .isEqualTo(NOON.plus(Duration.ofMinutes(11L * (PositionTrail.MAX_POINTS + 39))));
    }

    /** Retention has to hold for a device that stopped reporting, not only for one still going. */
    @Test
    void retained_forgetsPointsThatHaveAgedOutWithNoNewReportToPruneThem() {
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT, LON, NOON.plus(Duration.ofDays(20))));

        PositionTrail shown = trail.retained(NOON.plus(Duration.ofDays(31)));

        assertThat(shown.points()).singleElement()
            .satisfies(point -> assertThat(point.reportedAt()).isEqualTo(NOON.plus(Duration.ofDays(20))));
    }

    @Test
    void retained_keepsEverythingStillInsideTheWindow() {
        PositionTrail trail = trailFrom(
            at(LAT, LON, NOON),
            at(LAT, LON, NOON.plus(Duration.ofDays(20))));

        assertThat(trail.retained(NOON.plus(Duration.ofDays(21))).points()).hasSize(2);
    }

    @Test
    void empty_stateIsUnchangedByRetention() {
        assertThat(PositionTrail.empty().retained(NOON).points()).isEmpty();
    }
}
