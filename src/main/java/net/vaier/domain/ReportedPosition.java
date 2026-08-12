package net.vaier.domain;

import java.time.Instant;

/**
 * Coordinates a device's own browser gave via the W3C Geolocation API, with the accuracy it claimed and
 * the instant it was taken.
 *
 * <p>Held per {@link MachineId} on the machine's {@link MachinePosition} record — never by name and never
 * by VPN IP, both of which are mutable labels rather than identity.
 *
 * @param accuracyMetres the radius the browser gave, or null when it gave none.
 */
public record ReportedPosition(double latitude, double longitude, Double accuracyMetres,
                               Instant reportedAt) {

    /**
     * The refusal for a report that carries no coordinates — a body missing one, and a caller that sent no
     * body at all, which the domain cannot be handed to judge for itself. One wording, so the two cannot
     * drift into telling the same caller two different things.
     */
    public static IllegalArgumentException withoutCoordinates() {
        return new IllegalArgumentException("Latitude and longitude are required");
    }

    /**
     * A position as measured, refusing anything that is not a point on Earth. Takes boxed coordinates so
     * that a request body which simply omitted one is refused here, in the domain, rather than
     * NullPointerException-ing its way out of a controller.
     */
    public static ReportedPosition report(Double latitude, Double longitude, Double accuracyMetres,
                                          Instant reportedAt) {
        if (latitude == null || longitude == null) throw withoutCoordinates();
        require(isFinite(latitude) && latitude >= -90 && latitude <= 90,
            "Latitude must be between -90 and 90: " + latitude);
        require(isFinite(longitude) && longitude >= -180 && longitude <= 180,
            "Longitude must be between -180 and 180: " + longitude);
        require(accuracyMetres == null || (isFinite(accuracyMetres) && accuracyMetres >= 0),
            "Accuracy must not be negative: " + accuracyMetres);
        require(reportedAt != null, "A reported position must say when it was taken");
        return new ReportedPosition(latitude, longitude, accuracyMetres, reportedAt);
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
