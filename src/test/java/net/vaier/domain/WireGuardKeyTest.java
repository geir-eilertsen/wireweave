package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A public key minted on a device is the one thing about an {@code Enrolment} that Vaier did not
 * generate itself, so it is the one thing that has to be judged before anything moves. WireGuard keys
 * are 32 raw bytes in standard base64 — 43 characters and a single '=' — and nothing else may reach
 * {@code wg set}'s argv.
 */
class WireGuardKeyTest {

    /** A real, structurally valid WireGuard public key. */
    private static final String VALID = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";

    @Test
    void aRealWireGuardPublicKey_isAccepted() {
        assertThat(WireGuardKey.of(VALID).value()).isEqualTo(VALID);
    }

    @Test
    void surroundingWhitespace_isTrimmed() {
        // `wg pubkey` prints a trailing newline, and a URL round trip can pick up a space.
        assertThat(WireGuardKey.of("  " + VALID + "\n").value()).isEqualTo(VALID);
    }

    @Test
    void nullOrBlank_isRejected() {
        assertThatThrownBy(() -> WireGuardKey.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireGuardKey.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyOfTheWrongLength_isRejected() {
        assertThatThrownBy(() -> WireGuardKey.of("c2hvcnQ="))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32-byte");
    }

    @Test
    void aKeyThatIsNotBase64_isRejected() {
        assertThatThrownBy(() -> WireGuardKey.of("!!!!BA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg="))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shellMetacharactersNeverSurvive_soNothingCanReachWgSetsArgv() {
        // The reason this value object exists: the supplied key is used as-is by `wg set peer <key>`.
        assertThatThrownBy(() -> WireGuardKey.of(VALID + "; rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireGuardKey.of("$(id)"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WireGuardKey.of(VALID + "\nAllowedIPs = 0.0.0.0/0"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base64ThatDecodesTo32Bytes_butIsUnpadded_isRejected() {
        // WireGuard always prints the padded form; accepting anything else would let two spellings of
        // the same key exist, and a peer is looked up by the exact string.
        assertThatThrownBy(() -> WireGuardKey.of(VALID.substring(0, 43)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
