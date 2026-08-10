package net.vaier.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the kits out on the fleet still say what Vaier now knows.
 *
 * <p>This is the rule the whole feature was rebuilt around. A printed sheet was rejected for one reason: it
 * goes stale the moment a passphrase changes, and a stale sheet is worse than none because you believe you
 * are covered. A kit that is only rewritten when someone remembers to press a button has exactly the same
 * flaw — so the question "is what they are holding still true?" has to be one Vaier asks itself.
 *
 * <p>It is asked of the <em>contents</em>, not of a flag someone remembered to set. A dirty bit is lost on
 * restart, missed by whichever write path forgot to set it, and says nothing about a kit written before the
 * bit existed. The contents cannot lie about themselves.
 */
class SurvivalKitFreshnessTest {

    private static final String SHEET = "REPOSITORIES\napalveien5  the-passphrase\n";

    @Test
    void theSameContentsAlwaysFingerprintTheSame() {
        assertThat(SurvivalKitFreshness.fingerprintOf(SHEET))
            .isEqualTo(SurvivalKitFreshness.fingerprintOf(SHEET));
    }

    /**
     * A changed passphrase, an added repository, a renamed machine — every one of them reaches the kit as a
     * change to these contents, which is why one comparison covers causes nobody enumerated.
     */
    @Test
    void aChangeAnywhereInTheContentsChangesTheFingerprint() {
        assertThat(SurvivalKitFreshness.fingerprintOf(SHEET))
            .isNotEqualTo(SurvivalKitFreshness.fingerprintOf(SHEET + "colina27  another-passphrase\n"));
    }

    /** Never written is the staleness that matters most: the fleet holds nothing at all. */
    @Test
    void aFleetThatHasNeverBeenWrittenToIsStale() {
        assertThat(SurvivalKitFreshness.staleAgainst(null, SurvivalKitFreshness.fingerprintOf(SHEET)))
            .isTrue();
        assertThat(SurvivalKitFreshness.staleAgainst("  ", SurvivalKitFreshness.fingerprintOf(SHEET)))
            .isTrue();
    }

    @Test
    void unchangedContentsAreNotRewritten() {
        String fingerprint = SurvivalKitFreshness.fingerprintOf(SHEET);

        assertThat(SurvivalKitFreshness.staleAgainst(fingerprint, fingerprint)).isFalse();
    }

    @Test
    void changedContentsAreStale() {
        String written = SurvivalKitFreshness.fingerprintOf(SHEET);
        String now = SurvivalKitFreshness.fingerprintOf(SHEET + "colina27  another-passphrase\n");

        assertThat(SurvivalKitFreshness.staleAgainst(written, now)).isTrue();
    }

    /**
     * A host that was asleep is <em>not</em> this rule's business, and deliberately so: a partial rollout is
     * simply never recorded as written (see {@code SurvivalKitRollout.Result.reachedEveryDestination}), so it
     * arrives back here as a mismatch. Keeping it out of a flag is what makes the retry survive a restart.
     */
}
