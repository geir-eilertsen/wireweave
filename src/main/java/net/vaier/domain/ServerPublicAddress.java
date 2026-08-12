package net.vaier.domain;

import java.util.Locale;

/**
 * The Vaier server's own public address, as far as Vaier has managed to resolve it — and the one question
 * that gets asked of it: did this request come from us?
 *
 * <p>A <b>client peer</b> runs a full tunnel ({@code AllowedIPs = 0.0.0.0/0}), so its request to a gated
 * service leaves through the Vaier server and arrives at the forward-auth check wearing the server's own
 * public address. Geolocating that address places the <em>server</em>, never the person: 264 accesses by
 * the operator's own phone were drawn as a green dot in Frankfurt, where nobody had ever been.
 *
 * <p><b>Unknown is not "no".</b> Until the address has actually been resolved this calls nothing a hairpin,
 * so a lookup that has not happened yet can never blank the map.
 */
public record ServerPublicAddress(String value) {

    private static final ServerPublicAddress UNKNOWN = new ServerPublicAddress(null);

    /** No address resolved — every access is placed exactly as it would have been before this existed. */
    public static ServerPublicAddress unknown() {
        return UNKNOWN;
    }

    /** The resolved address, or {@link #unknown()} when the resolution yielded nothing to compare against. */
    public static ServerPublicAddress of(String publicIp) {
        String normalized = normalize(publicIp);
        return normalized == null ? UNKNOWN : new ServerPublicAddress(normalized);
    }

    /**
     * This address after a resolution attempt yielded {@code resolvedIp} — unchanged when it yielded
     * nothing. The mirror of unknown-is-not-"no": one quiet minute at the metadata endpoint must not
     * un-know an address Vaier has already resolved, or every hairpin is drawn as a place again until the
     * next successful lookup.
     */
    public ServerPublicAddress refreshedWith(String resolvedIp) {
        ServerPublicAddress refreshed = of(resolvedIp);
        return refreshed.value == null ? this : refreshed;
    }

    /**
     * Whether an access from {@code callerIp} is really this server's own traffic turning around — a
     * hairpinned access, which has no place and belongs in the unplaceable source.
     */
    public boolean isHairpin(String callerIp) {
        return value != null && value.equals(normalize(callerIp));
    }

    /** Trimmed and lower-cased: IPv6 hex reaches us in either case from two different sources. */
    private static String normalize(String ip) {
        if (ip == null) return null;
        String trimmed = ip.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
