package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportedPositionTest {

    private static final Instant NOW = Instant.parse("2026-08-11T18:00:00Z");

    @Test
    void report_keepsWhatTheBrowserMeasured() {
        ReportedPosition position = ReportedPosition.report(63.4305, 10.3951, 12.0, NOW);

        assertThat(position.latitude()).isEqualTo(63.4305);
        assertThat(position.longitude()).isEqualTo(10.3951);
        assertThat(position.accuracyMetres()).isEqualTo(12.0);
        assertThat(position.reportedAt()).isEqualTo(NOW);
    }

    /** The W3C API's accuracy is optional in practice; a position without it is still a position. */
    @Test
    void report_acceptsAPositionWithNoAccuracy() {
        assertThat(ReportedPosition.report(63.4305, 10.3951, null, NOW).accuracyMetres()).isNull();
    }

    @Test
    void report_rejectsAMissingCoordinate() {
        assertThatThrownBy(() -> ReportedPosition.report(null, 10.3951, 12.0, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportedPosition.report(63.4305, null, 12.0, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void report_rejectsALatitudeOffTheGlobe() {
        assertThatThrownBy(() -> ReportedPosition.report(90.1, 10.0, null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportedPosition.report(-90.1, 10.0, null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void report_rejectsALongitudeOffTheGlobe() {
        assertThatThrownBy(() -> ReportedPosition.report(63.0, 180.1, null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportedPosition.report(63.0, -180.1, null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void report_rejectsNonsenseThatIsNotANumber() {
        assertThatThrownBy(() -> ReportedPosition.report(Double.NaN, 10.0, null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReportedPosition.report(63.0, Double.POSITIVE_INFINITY, null, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void report_rejectsANegativeAccuracy() {
        assertThatThrownBy(() -> ReportedPosition.report(63.0, 10.0, -1.0, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void report_rejectsAPositionWithNoInstantItWasTakenAt() {
        assertThatThrownBy(() -> ReportedPosition.report(63.0, 10.0, 12.0, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** The poles and the antimeridian are real places, so the bounds are inclusive. */
    @Test
    void report_acceptsTheEdgesOfTheGlobe() {
        assertThat(ReportedPosition.report(90.0, 180.0, 0.0, NOW).latitude()).isEqualTo(90.0);
        assertThat(ReportedPosition.report(-90.0, -180.0, 0.0, NOW).longitude()).isEqualTo(-180.0);
    }
}
