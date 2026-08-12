package net.vaier.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * The binding between one browser and one machine that lets that browser record the machine's
 * {@link ReportedPosition} from anywhere — off the tunnel included, which is the ordinary case for a phone
 * whose WireGuard connection drops all day.
 *
 * <p>The token is opaque and verified against this stored record, deliberately rather than being a
 * self-contained signed blob: a signature with no server-side record cannot be revoked, and revocation is
 * the whole point of the privacy escape hatch. One token per machine — a fresh claim supersedes the last.
 *
 * <p><b>Proportionate on purpose.</b> Forging a claim buys a wrong dot on a map: no credential, no access
 * to anything. That is why a stored random token is the right weight of mechanism here, and why this is
 * not device attestation.
 */
public record DeviceClaim(String token, Instant claimedAt) {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    public DeviceClaim {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A device claim must carry a token");
        }
        if (claimedAt == null) {
            throw new IllegalArgumentException("A device claim must say when it was made");
        }
    }

    /** A fresh claim. The only way a token is ever minted. */
    public static DeviceClaim mint(Instant claimedAt) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return new DeviceClaim(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), claimedAt);
    }

    /** Whether {@code candidate} is this claim's token. Compared in constant time, as tokens are. */
    public boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }
}
