package net.fjordomatic.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Whether the kits the fleet is holding still say what Fjord knows.
 *
 * <p><b>Why this exists at all.</b> A printed recovery sheet was rejected for one reason: it goes stale the
 * moment a passphrase changes, and a stale sheet is worse than none, because you believe you are covered. A
 * kit rewritten only when someone remembers to press a button has precisely the same flaw, with a nicer
 * surface. So the question has to be one Fjord asks itself, on a timer, without being told.
 *
 * <p><b>Why it asks the contents and not a flag.</b> The obvious design is a dirty bit set by every write
 * path that touches a repository, a job, a server or the fleet. It is the wrong one: a bit is lost on
 * restart, missed by whichever path forgot to set it, and says nothing at all about kits written before it
 * existed — three different ways to believe the fleet is covered when it is not. A fingerprint of what the
 * kit would say today, compared with what it said when it was last written, cannot be wrong about any of
 * them, and it covers causes nobody thought to enumerate: a machine renamed, a repository adopted by hand, a
 * job re-pointed. Every one of them reaches the kit as a change to its contents.
 *
 * <p>The one cause it cannot see is a changed <em>kit passphrase</em> — the contents are identical, only the
 * lock differs. That is handled where it happens, by
 * {@link FjordConfig#withSurvivalKitPassphrase(String)} forgetting the fingerprint, which lands here as
 * "never written" and rewrites on the next sweep.
 */
public final class SurvivalKitFreshness {

    private SurvivalKitFreshness() {}

    /**
     * A fingerprint of what a kit would say. SHA-256, so two different sheets never collide into "unchanged"
     * — the failure mode of a cheaper hash here is a fleet holding kits nobody notices are wrong.
     *
     * <p>Of the contents, deliberately, and never of the ciphertext: every write mints a fresh salt, so two
     * encryptions of an identical sheet differ in every byte and would read as a change every single sweep.
     */
    public static String fingerprintOf(String sheet) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sheet.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is genuinely absent, silently treating kits as fresh
            // would be the one outcome that lets them rot unnoticed.
            throw new IllegalStateException("SHA-256 is unavailable, so kit freshness cannot be judged", e);
        }
    }

    /**
     * Whether the fleet's kits need writing again.
     *
     * <p>A host that was asleep for the last write needs no rule of its own here. A rollout that did not
     * reach every destination is never recorded as written at all, so it comes back as "never" — which also
     * means the retry survives a restart, where a flag in memory would not.
     *
     * @param lastWritten the fingerprint recorded when kits were last written; null or blank means never,
     *                    which is the staleness that matters most — the fleet is holding nothing
     * @param current     the fingerprint of what a kit would say now
     */
    public static boolean staleAgainst(String lastWritten, String current) {
        if (lastWritten == null || lastWritten.isBlank()) {
            return true;
        }
        return !lastWritten.equals(current);
    }
}
