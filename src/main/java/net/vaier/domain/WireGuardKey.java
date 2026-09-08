package net.vaier.domain;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A WireGuard public key, as WireGuard itself prints one: 32 raw bytes in standard base64 — 43
 * characters and a single {@code =}.
 *
 * <p>Every other key in Vaier is minted by {@code wg genkey} inside the WireGuard container and is
 * trusted because Vaier made it. An {@code Enrolment}'s key is the exception: it is minted on the
 * device and arrives through a browser query string, so it is the one key Vaier must judge — and it
 * has to be judged <em>before</em> any state changes, because it is used as-is as an argv token to
 * {@code wg set ... peer <key>} and is written verbatim into the peer's config metadata.
 *
 * <p>Only the padded 44-character spelling is accepted. Two spellings of one key would be two peers
 * as far as every lookup is concerned.
 */
public record WireGuardKey(String value) {

    /** Standard base64 of exactly 32 bytes: 43 alphabet characters and one pad. */
    private static final Pattern CANONICAL = Pattern.compile("[A-Za-z0-9+/]{43}=");

    public WireGuardKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WireGuard key must not be blank");
        }
        if (!CANONICAL.matcher(value).matches() || Base64.getDecoder().decode(value).length != 32) {
            throw new IllegalArgumentException(
                "WireGuard key must be a 32-byte base64 key as WireGuard prints it");
        }
    }

    /** Reads a supplied key, trimming the whitespace a {@code wg pubkey} newline or a URL round trip adds. */
    public static WireGuardKey of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("WireGuard key must not be blank");
        }
        return new WireGuardKey(raw.trim());
    }
}
