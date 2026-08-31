package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.HexFormat;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FleetCredentialTest {

    private static final String CONTENT = "{\"token\":\"abc123\"}";

    private static FleetCredential credential() {
        return FleetCredential.of("claude-oauth", "~/.claude/.credentials.json", "0600", CONTENT);
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- shape and validation -------------------------------------------------------------

    @Test
    void of_defaultsAMissingModeTo0600() {
        assertThat(FleetCredential.of("t", "/etc/t", null, CONTENT).mode()).isEqualTo("0600");
        assertThat(FleetCredential.of("t", "/etc/t", "  ", CONTENT).mode()).isEqualTo("0600");
    }

    @Test
    void of_isNotDistributedUntilItHasBeen() {
        assertThat(credential().distributed()).isFalse();
    }

    @Test
    void name_mustBeASafeIdentifier() {
        assertThatThrownBy(() -> FleetCredential.of("a; rm -rf ~", "/etc/t", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("has space", "/etc/t", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("  ", "/etc/t", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(FleetCredential.of("claude-oauth_2", "/etc/t", "0600", CONTENT).name())
            .isEqualTo("claude-oauth_2");
    }

    @Test
    void targetPath_mustBeAbsoluteOrHomeRelative() {
        assertThatThrownBy(() -> FleetCredential.of("t", "relative/path", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("t", "~", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(FleetCredential.of("t", "/etc/vaier/t", "0600", CONTENT).targetPath())
            .isEqualTo("/etc/vaier/t");
    }

    @Test
    void targetPath_rejectsShellMetacharactersAndTraversal() {
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/$(whoami)", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/a b", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("t", "~/../../etc/shadow", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        // A tilde is only meaningful at the front; anywhere else it is just a stray character.
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/~/t", "0600", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mode_mustBeThreeOrFourOctalDigits() {
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/t", "rw-", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/t", "0900", CONTENT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(FleetCredential.of("t", "/etc/t", "644", CONTENT).mode()).isEqualTo("644");
    }

    @Test
    void content_mustNotBeBlank() {
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/t", "0600", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FleetCredential.of("t", "/etc/t", "0600", ""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- digest ---------------------------------------------------------------------------

    @Test
    void digest_isTheSha256OfTheContent() throws Exception {
        assertThat(credential().digest()).isEqualTo(sha256Hex(CONTENT));
    }

    @Test
    void digest_changesWithTheContent() {
        assertThat(FleetCredential.of("t", "/etc/t", "0600", "one").digest())
            .isNotEqualTo(FleetCredential.of("t", "/etc/t", "0600", "two").digest());
    }

    // ---- redaction ------------------------------------------------------------------------

    @Test
    void toView_carriesNoSecretBytes() {
        FleetCredentialView view = credential().toView();

        assertThat(view.name()).isEqualTo("claude-oauth");
        assertThat(view.targetPath()).isEqualTo("~/.claude/.credentials.json");
        assertThat(view.mode()).isEqualTo("0600");
        assertThat(view.hasSecret()).isTrue();
        assertThat(view.distributed()).isFalse();
        assertThat(view.toString()).doesNotContain("abc123");
    }

    @Test
    void toString_neverCarriesTheSecret() {
        assertThat(credential().toString()).doesNotContain("abc123");
    }

    // ---- shell path -----------------------------------------------------------------------

    @Test
    void shellPath_expandsALeadingTildeToHomeAndSingleQuotesTheRest() {
        assertThat(credential().shellPath()).isEqualTo("\"$HOME\"'/.claude/.credentials.json'");
    }

    @Test
    void shellPath_singleQuotesAnAbsolutePath() {
        assertThat(FleetCredential.of("t", "/etc/vaier/t", "0600", CONTENT).shellPath())
            .isEqualTo("'/etc/vaier/t'");
    }

    // ---- rendered commands ----------------------------------------------------------------

    @Test
    void writeCommand_deliversTheContentBase64Encoded() {
        String command = credential().writeCommand();

        String expected = Base64.getEncoder().encodeToString(CONTENT.getBytes(StandardCharsets.UTF_8));
        assertThat(command).contains("'" + expected + "'");
        assertThat(command).contains("base64 -d");
        // The secret itself never appears in the command line.
        assertThat(command).doesNotContain("abc123");
    }

    @Test
    void writeCommand_createsTheParentDirectoryAndChmodsToTheCredentialsMode() {
        String command = FleetCredential.of("t", "/etc/vaier/t", "0640", CONTENT).writeCommand();

        assertThat(command).contains("umask 077");
        assertThat(command).contains("mkdir -p \"$(dirname \"$P\")\"");
        assertThat(command).contains("chmod 0640 \"$P\"");
    }

    @Test
    void writeCommand_reportsBackWhatItActuallyWroteSoTheWriteIsNeverTrusted() {
        String command = credential().writeCommand();

        assertThat(command).contains("id -un");
        assertThat(command).contains("stat -c %U");
        assertThat(command).contains("stat -c %a");
        assertThat(command).contains("sha256sum");
        assertThat(command).contains(FleetCredential.REPORT_MARKER);
    }

    @Test
    void verifyCommand_neverShipsTheSecret() {
        String command = credential().verifyCommand();

        assertThat(command).doesNotContain("abc123");
        assertThat(command).doesNotContain("base64 -d");
        assertThat(command).contains("sha256sum");
        assertThat(command).contains(FleetCredential.REPORT_MARKER);
    }

    @Test
    void removeCommand_removesThePathAndConfirmsItIsGone() {
        String command = credential().removeCommand();

        assertThat(command).contains("rm -f \"$P\"");
        assertThat(command).contains(FleetCredential.REPORT_MARKER);
        assertThat(command).doesNotContain("abc123");
    }

    @Test
    void everyCommandSingleQuotesThePathThroughOneAssignment() {
        for (String command : new String[] {
            credential().writeCommand(), credential().verifyCommand(), credential().removeCommand()}) {
            assertThat(command).startsWith("P=\"$HOME\"'/.claude/.credentials.json'; ");
        }
    }

    // ---- reading the report ---------------------------------------------------------------

    private static String present(String user, String owner, String mode, String digest) {
        return "some noise\n" + FleetCredential.REPORT_MARKER + " state=present user=" + user
            + " owner=" + owner + " mode=" + mode + " digest=" + digest;
    }

    private static String absent() {
        return FleetCredential.REPORT_MARKER + " state=absent user=geir";
    }

    @Test
    void readVerification_currentWhenPresentOwnedByTheLoginUserAtTheRightModeAndDigest() throws Exception {
        assertThat(credential().readVerification(present("geir", "geir", "600", sha256Hex(CONTENT))))
            .isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void readVerification_comparesModeIgnoringTheLeadingZero() throws Exception {
        // The credential says 0600; stat says 600. They are the same mode.
        assertThat(credential().readVerification(present("geir", "geir", "600", sha256Hex(CONTENT))))
            .isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void readVerification_missingWhenTheFileIsNotThere() {
        assertThat(credential().readVerification(absent())).isEqualTo(FleetCredentialState.MISSING);
    }

    @Test
    void readVerification_staleWhenTheDigestDiffers() {
        assertThat(credential().readVerification(present("geir", "geir", "600", "deadbeef")))
            .isEqualTo(FleetCredentialState.STALE);
    }

    @Test
    void readVerification_staleWhenTheModeOrOwnerDrifted() throws Exception {
        String digest = sha256Hex(CONTENT);
        assertThat(credential().readVerification(present("geir", "geir", "644", digest)))
            .isEqualTo(FleetCredentialState.STALE);
        assertThat(credential().readVerification(present("geir", "root", "600", digest)))
            .isEqualTo(FleetCredentialState.STALE);
    }

    @Test
    void readVerification_failedWhenTheHostSaidNothingWeUnderstand() {
        assertThat(credential().readVerification("bash: stat: command not found"))
            .isEqualTo(FleetCredentialState.FAILED);
        assertThat(credential().readVerification(null)).isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void readWriteOutcome_currentOnlyWhenTheFileOnDiskMatchesInEveryRespect() throws Exception {
        assertThat(credential().readWriteOutcome(present("geir", "geir", "600", sha256Hex(CONTENT))))
            .isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void readWriteOutcome_failsOnAWrongOwner_theSilentlyUnreadableCase() throws Exception {
        // uid-1000 Vaier writing a root-owned file is the failure that looks like a success.
        assertThat(credential().readWriteOutcome(present("geir", "root", "600", sha256Hex(CONTENT))))
            .isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void readWriteOutcome_failsOnAWrongMode() throws Exception {
        assertThat(credential().readWriteOutcome(present("geir", "geir", "644", sha256Hex(CONTENT))))
            .isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void readWriteOutcome_failsWhenTheContentDidNotLand() {
        assertThat(credential().readWriteOutcome(present("geir", "geir", "600", "")))
            .isEqualTo(FleetCredentialState.FAILED);
        assertThat(credential().readWriteOutcome(absent())).isEqualTo(FleetCredentialState.FAILED);
        assertThat(credential().readWriteOutcome("permission denied"))
            .isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void readWithdrawal_withdrawnOnlyWhenTheFileIsGone() throws Exception {
        assertThat(credential().readWithdrawal(absent())).isEqualTo(FleetCredentialState.WITHDRAWN);
        assertThat(credential().readWithdrawal(present("geir", "geir", "600", sha256Hex(CONTENT))))
            .isEqualTo(FleetCredentialState.FAILED);
        assertThat(credential().readWithdrawal("nope")).isEqualTo(FleetCredentialState.FAILED);
    }

    // ---- the reconcile guard ---------------------------------------------------------------

    @Test
    void neverDistributed_isNeverReconciled() {
        assertThat(credential().shouldReconcile()).isFalse();
    }

    @Test
    void markDistributed_isWhatEarnsACredentialItsBackgroundHealing() {
        FleetCredential distributed = credential().markDistributed();

        assertThat(distributed.distributed()).isTrue();
        assertThat(distributed.shouldReconcile()).isTrue();
        assertThat(distributed.content()).isEqualTo(CONTENT);
    }

    @Test
    void markWithdrawn_stopsTheBackgroundHealingSoRevocationSticks() {
        assertThat(credential().markDistributed().markWithdrawn().shouldReconcile()).isFalse();
    }

    @Test
    void aSaveCarriesTheDistributedStandingForward_soEditingTheModeDoesNotSilentlyStopReconciling() {
        FleetCredential edited = FleetCredential.of("claude-oauth", "~/.claude/.credentials.json", "0640",
            CONTENT).carryingStandingFrom(Optional.of(credential().markDistributed()));

        assertThat(edited.distributed()).isTrue();
        assertThat(edited.mode()).isEqualTo("0640");
    }

    @Test
    void aFreshCredentialInheritsNoStanding() {
        assertThat(credential().carryingStandingFrom(Optional.empty()).distributed()).isFalse();
        assertThat(credential().markDistributed().carryingStandingFrom(Optional.of(credential()))
            .distributed()).isFalse();
    }
}
