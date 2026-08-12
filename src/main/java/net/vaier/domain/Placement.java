package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The decided answer to "where is this machine": the coordinates, which evidence they came from, when that
 * evidence was taken, and whether it has gone stale. Absent whenever Vaier has no honest answer.
 *
 * @param asOf           when the evidence was taken — the measurement's instant for a
 *                       {@link PlacementSource#REPORTED reported position}, the latest handshake for an
 *                       {@link PlacementSource#ISP_ESTIMATE ISP estimate}.
 * @param accuracyMetres the reported radius; null for an ISP estimate, which has no meaningful one.
 * @param place          "city, country" when the ISP estimate names them; null otherwise, and always null
 *                       for a reported position — Vaier does no reverse geocoding.
 */
public record Placement(double latitude, double longitude, PlacementSource source, Instant asOf,
                        Double accuracyMetres, boolean stale, String place) {

    /** Past this, a reported position is still the best evidence there is — just old enough to say so. */
    private static final Duration STALE_AFTER = Duration.ofHours(24);

    /**
     * Where to draw this machine, given what Vaier knows.
     *
     * <p><b>A reported position always wins, even against a fresher ISP estimate.</b> A coarse measurement
     * does not outrank a precise one merely by being recent: the ISP estimate locates the carrier's
     * registry entry, not the device. Freshness is communicated by showing age — {@link #asOf} and
     * {@link #stale} — never by letting the coarse source win.
     *
     * <p>And a disconnected peer gets nothing. {@code wg} keeps reporting the last endpoint it saw, so a
     * dead tunnel's ISP estimate is a day-old carrier IP drawn as though the device were there now.
     */
    public static Optional<Placement> decide(ReportedPosition reported, GeoLocation ispEstimate,
                                             boolean connected, Instant latestHandshake, Instant now) {
        if (reported != null) {
            return Optional.of(new Placement(reported.latitude(), reported.longitude(),
                PlacementSource.REPORTED, reported.reportedAt(), reported.accuracyMetres(),
                isStale(reported.reportedAt(), now), null));
        }
        if (!connected || ispEstimate == null) return Optional.empty();
        return Optional.of(new Placement(ispEstimate.latitude(), ispEstimate.longitude(),
            PlacementSource.ISP_ESTIMATE, latestHandshake, null, false, place(ispEstimate)));
    }

    private static boolean isStale(Instant reportedAt, Instant now) {
        return reportedAt.isBefore(now.minus(STALE_AFTER));
    }

    private static String place(GeoLocation estimate) {
        return Stream.of(estimate.city(), estimate.country())
            .filter(part -> part != null && !part.isBlank())
            .reduce((city, country) -> city + ", " + country)
            .orElse(null);
    }
}
