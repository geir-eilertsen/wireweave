package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementTest {

    private static final Instant NOW = Instant.parse("2026-08-11T18:00:00Z");

    /** Telenor HQ at Fornebu — where DB-IP puts the whole 77.16.0.0 block, whoever is holding the phone. */
    private static final GeoLocation FORNEBU = new GeoLocation(59.8989, 10.6324, "Oslo", "Norway");

    private static ReportedPosition reportedAt(Instant when) {
        return ReportedPosition.report(63.4305, 10.3951, 12.0, when);
    }

    // --- 1. a reported position ---

    @Test
    void withAReportedPosition_placesTheDeviceWhereItSaidItWas() {
        Optional<Placement> placement = Placement.decide(
            reportedAt(NOW.minusSeconds(60)), null, false, null, NOW);

        assertThat(placement).get().satisfies(p -> {
            assertThat(p.latitude()).isEqualTo(63.4305);
            assertThat(p.longitude()).isEqualTo(10.3951);
            assertThat(p.source()).isEqualTo(PlacementSource.REPORTED);
            assertThat(p.asOf()).isEqualTo(NOW.minusSeconds(60));
            assertThat(p.accuracyMetres()).isEqualTo(12.0);
            assertThat(p.stale()).isFalse();
            assertThat(p.place()).isNull();
        });
    }

    /**
     * The crux. A carrier's registry point is evidence about the carrier, so it never outranks a real
     * measurement — not even a real measurement from yesterday.
     */
    @Test
    void aReportedPositionOutranksAFresherIspEstimate() {
        Optional<Placement> placement = Placement.decide(
            reportedAt(NOW.minusSeconds(23 * 3600)), FORNEBU, true, NOW, NOW);

        assertThat(placement).get().satisfies(p -> {
            assertThat(p.source()).isEqualTo(PlacementSource.REPORTED);
            assertThat(p.latitude()).isEqualTo(63.4305);
        });
    }

    // --- the staleness threshold ---

    @Test
    void aReportExactlyTwentyFourHoursOldIsNotYetStale() {
        assertThat(Placement.decide(reportedAt(NOW.minusSeconds(24 * 3600)), null, false, null, NOW))
            .get().satisfies(p -> assertThat(p.stale()).isFalse());
    }

    @Test
    void aReportOlderThanTwentyFourHoursIsStale() {
        assertThat(Placement.decide(reportedAt(NOW.minusSeconds(24 * 3600 + 1)), null, false, null, NOW))
            .get().satisfies(p -> assertThat(p.stale()).isTrue());
    }

    /** Stale is a label on the answer, never a reason to fall back to the coarse source. */
    @Test
    void aStaleReportStillOutranksAConnectedPeersIspEstimate() {
        assertThat(Placement.decide(reportedAt(NOW.minusSeconds(5 * 86400)), FORNEBU, true, NOW, NOW))
            .get().satisfies(p -> {
                assertThat(p.source()).isEqualTo(PlacementSource.REPORTED);
                assertThat(p.stale()).isTrue();
            });
    }

    // --- 2. no report, peer connected ---

    @Test
    void withNoReportAndAConnectedPeer_fallsBackToTheIspEstimate() {
        Instant handshake = NOW.minusSeconds(30);

        assertThat(Placement.decide(null, FORNEBU, true, handshake, NOW)).get().satisfies(p -> {
            assertThat(p.latitude()).isEqualTo(59.8989);
            assertThat(p.longitude()).isEqualTo(10.6324);
            assertThat(p.source()).isEqualTo(PlacementSource.ISP_ESTIMATE);
            assertThat(p.asOf()).isEqualTo(handshake);
            assertThat(p.accuracyMetres()).isNull();
            assertThat(p.stale()).isFalse();
            assertThat(p.place()).isEqualTo("Oslo, Norway");
        });
    }

    @Test
    void anIspEstimateWithNoCityOrCountryNamesNoPlace() {
        assertThat(Placement.decide(null, new GeoLocation(59.9, 10.6, null, null), true, NOW, NOW))
            .get().satisfies(p -> assertThat(p.place()).isNull());
    }

    // --- 3. no report, peer disconnected — the reported bug ---

    /**
     * The phone whose tunnel has been down 35 hours. {@code wg} still reports the last endpoint it saw,
     * and drawing it says "connecting from Fornebu" about a day-and-a-half-old carrier IP.
     */
    @Test
    void withNoReportAndADisconnectedPeer_thereIsNoPlacementAtAll() {
        assertThat(Placement.decide(null, FORNEBU, false, NOW.minusSeconds(35 * 3600), NOW)).isEmpty();
    }

    // --- 4. no coordinates from either source ---

    @Test
    void withNeitherAReportNorAnIspEstimate_thereIsNoPlacement() {
        assertThat(Placement.decide(null, null, true, NOW, NOW)).isEmpty();
    }
}
