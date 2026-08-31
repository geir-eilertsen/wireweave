package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The shell Vaier speaks for a Claude sign-in, and how it reads the answers back.
 *
 * <p>Everything here goes through the CLI's own {@code claude auth} subcommands — {@code auth login},
 * {@code auth logout}, {@code auth status --json}. That is the whole design: three commands Anthropic
 * built to be driven, rather than Vaier inferring the same facts from a REPL banner and a file on disk.
 *
 * <p>The load-bearing test in this file is
 * {@link #theStatusCommandNeverGoesNearTheCredentialFile()}. Anthropic's terms forbid a third party
 * collecting, storing or intermediating Claude credentials, so Vaier asks the CLI a question and reads
 * its answer. A future edit that reaches for the credential file has to delete that test to land, which
 * is exactly the amount of friction that decision deserves.
 */
class ClaudeSignInTest {

    // --- The command that starts the sign-in --------------------------------------------------------

    /**
     * The CLI has a subcommand whose entire job is this. Driving {@code auth login} rather than bare
     * {@code claude} means Vaier depends on a documented interface instead of on the startup banner of a
     * REPL that has no obligation to keep printing a URL.
     */
    @Test
    void startsThePurposeBuiltAuthLoginSubcommand() {
        String command = ClaudeSignIn.startCommand();

        assertThat(command).contains("claude auth login --claudeai");
        assertThat(command).doesNotContain("curl").doesNotContain("wget");
        assertThat(command).doesNotContain(".credentials.json");
    }

    /** A Claude subscription, not Console API billing — that is the account the operator is signing in. */
    @Test
    void signsInWithTheClaudeSubscriptionRatherThanConsoleBilling() {
        assertThat(ClaudeSignIn.startCommand()).contains("--claudeai").doesNotContain("--console");
    }

    /**
     * The waiting CLI must outlive the HTTP request that started it, so it runs inside tmux. Asserted on
     * the pieces rather than one literal: the command is nested inside a login shell, so its inner quotes
     * arrive escaped and a substring match on the quoted session name would only be testing the escaping.
     */
    @Test
    void runsTheCliInsideAPersistentShellSoItSurvivesBetweenRequests() {
        assertThat(ClaudeSignIn.startCommand())
            .contains("tmux new-session -A -D -s")
            .contains(ClaudeSignIn.sessionName());
    }

    /**
     * Three levels of nesting — a login shell, wrapping tmux, wrapping another login shell — is exactly
     * where quoting breaks silently: a mis-escaped quote produces a command that runs, does the wrong
     * thing, and reports a plausible failure. So the generated shell is handed to a real parser.
     */
    @Test
    void everyGeneratedCommandIsValidShell() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "needs bash");
        for (String command : List.of(ClaudeSignIn.startCommand(), ClaudeSignIn.statusCommand(),
            ClaudeSignIn.signOutCommand(), ClaudeSignIn.endCommand())) {
            Process check = new ProcessBuilder("/bin/bash", "-n", "-c", command)
                .redirectErrorStream(true).start();
            String complaint = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            check.waitFor(20, TimeUnit.SECONDS);
            assertThat(check.exitValue()).as("bash rejected: %s", complaint).isZero();
        }
    }

    /** A machine with no Claude installed says so at once instead of leaving the operator on a spinner. */
    @Test
    void saysSoImmediatelyWhenTheMachineHasNoClaudeInstalled() {
        assertThat(ClaudeSignIn.startCommand())
            .contains("command -v claude")
            .contains(ClaudeSignInOutput.CLI_ABSENT_MARKER);
    }

    /** Its own reserved tmux session, so a sign-in can never land in an operator's terminal pane. */
    @Test
    void usesItsOwnReservedSessionName() {
        assertThat(ClaudeSignIn.sessionName()).isEqualTo("vaier-claude-sign-in");
        assertThat(ClaudeSignIn.endCommand()).contains("kill-session -t 'vaier-claude-sign-in'");
    }

    // --- Signing out ---------------------------------------------------------------------------------

    /**
     * Sign-out is the CLI's own {@code auth logout} and nothing else. Deleting the credential file would
     * work and is exactly the line Vaier stays behind: removing someone's credential is manipulating it.
     * Vaier asks the binary that owns it to let it go.
     */
    @Test
    void signsOutByAskingTheCliRatherThanDeletingItsCredential() {
        String command = ClaudeSignIn.signOutCommand();

        assertThat(command).contains("claude auth logout");
        assertThat(command).doesNotContain(".credentials.json")
            .doesNotContain("rm ").doesNotContain("unlink").doesNotContain("shred");
    }

    /** Signing out a machine that has no Claude on it is a plain fact, not a failure. */
    @Test
    void signingOutSaysSoWhenTheMachineHasNoClaudeInstalled() {
        assertThat(ClaudeSignIn.signOutCommand())
            .contains("command -v claude")
            .contains(ClaudeSignInOutput.CLI_ABSENT_MARKER);
    }

    // --- The code the operator pastes back ----------------------------------------------------------

    @Test
    void sendsThePastedCodeAsAKeystrokeLineIntoTheWaitingProcess() {
        assertThat(ClaudeSignIn.keystrokesForCode("  abc123#state-xyz \r\n "))
            .isEqualTo("abc123#state-xyz\n");
    }

    /**
     * If the CLI has already exited, whatever is typed lands in a shell instead — so anything that could
     * be a second command is refused before it is ever written.
     */
    @Test
    void refusesACodeThatCouldCarryAShellCommand() {
        assertThatThrownBy(() -> ClaudeSignIn.keystrokesForCode("abc; rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("code");
        assertThatThrownBy(() -> ClaudeSignIn.keystrokesForCode("abc\nwhoami"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaudeSignIn.keystrokesForCode("$(id)"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesABlankCode() {
        assertThatThrownBy(() -> ClaudeSignIn.keystrokesForCode("   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaudeSignIn.keystrokesForCode(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Asking a machine whether it is signed in ---------------------------------------------------

    /**
     * The CLI is asked, and its answer is read. Vaier never opens, copies, digests or stats the file the
     * CLI keeps its credential in — there is nothing in it Vaier is permitted to see, so there is nothing
     * in it Vaier asks for.
     */
    @Test
    void theStatusCommandNeverGoesNearTheCredentialFile() {
        String command = ClaudeSignIn.statusCommand();

        assertThat(command).doesNotContain(".credentials.json").doesNotContain("CLAUDE_CONFIG_DIR")
            .doesNotContain("cat ").doesNotContain("base64").doesNotContain("sha256")
            .doesNotContain("shasum").doesNotContain("stat ").doesNotContain("head ");
        assertThat(command).contains("claude auth status --json");
    }

    @Test
    void readsALoggedInMachineAsSignedIn() {
        assertThat(ClaudeSignIn.readStatus(statusJson(true))).isEqualTo(ClaudeSignInState.SIGNED_IN);
    }

    @Test
    void readsALoggedOutMachineAsSignedOut() {
        assertThat(ClaudeSignIn.readStatus(statusJson(false))).isEqualTo(ClaudeSignInState.SIGNED_OUT);
    }

    /** Not an error, and not an alarm — just a machine that has no Claude on it. */
    @Test
    void readsAMachineWithoutTheCliAsNotInstalled() {
        assertThat(ClaudeSignIn.readStatus(ClaudeSignInOutput.CLI_ABSENT_MARKER + "\n"))
            .isEqualTo(ClaudeSignInState.NOT_INSTALLED);
    }

    /**
     * An older CLI has no {@code auth} subcommand and answers with a usage error on stderr and nothing
     * useful on stdout. That is unknown, and it must never read as signed out — telling an operator a
     * machine is signed out when Vaier simply could not ask would send them to re-run a sign-in that was
     * never needed.
     */
    @Test
    void readsAnOlderCliWithNoAuthSubcommandAsUnknownRatherThanSignedOut() {
        assertThat(ClaudeSignIn.readStatus("error: unknown command 'auth'\n"))
            .isEqualTo(ClaudeSignInState.UNKNOWN);
        assertThat(ClaudeSignIn.readStatus("")).isEqualTo(ClaudeSignInState.UNKNOWN);
        assertThat(ClaudeSignIn.readStatus(null)).isEqualTo(ClaudeSignInState.UNKNOWN);
    }

    /** Malformed or truncated JSON is unknown too. Never a crash, and never an optimistic guess. */
    @Test
    void readsBrokenJsonAsUnknownAndNeverThrows() {
        assertThat(ClaudeSignIn.readStatus("{\"loggedIn\": tr")).isEqualTo(ClaudeSignInState.UNKNOWN);
        assertThat(ClaudeSignIn.readStatus("{}")).isEqualTo(ClaudeSignInState.UNKNOWN);
        assertThat(ClaudeSignIn.readStatus("{\"loggedIn\": \"yes\"}"))
            .isEqualTo(ClaudeSignInState.UNKNOWN);
        assertThat(ClaudeSignIn.readStatus("not json at all")).isEqualTo(ClaudeSignInState.UNKNOWN);
    }

    /**
     * An SSH command can come back with a login banner or a shell warning wrapped around the answer, and
     * the CLI pretty-prints its JSON across a dozen lines. The object is found inside whatever else
     * arrived.
     */
    @Test
    void findsTheJsonInsideAMachinesBannerAndAcrossManyLines() {
        String noisy = "Welcome to Ubuntu 24.04 LTS\n" + statusJson(true) + "\nConnection closed.\n";

        assertThat(ClaudeSignIn.readStatus(noisy)).isEqualTo(ClaudeSignInState.SIGNED_IN);
    }

    /**
     * Now that every command runs in a <em>login</em> shell, the machine's own profile gets a chance to
     * talk first — and a {@code /etc/profile.d} script that prints a brace would otherwise swallow the
     * real answer, because a first-brace-to-last-brace grab would span the chatter and fail to parse. The
     * object is located by actually finding a balanced one, not by bracketing the whole output.
     */
    @Test
    void findsTheJsonEvenWhenTheProfileChattersInBraces() {
        String chatty = "shell-init: ${HOME} not set\nrunning hook {start}\n" + statusJson(true) + "\n";

        assertThat(ClaudeSignIn.readStatus(chatty)).isEqualTo(ClaudeSignInState.SIGNED_IN);
        assertThat(ClaudeSignIn.readAccount(chatty)).isPresent();
    }

    /** A brace in the chatter and no answer at all is still unknown, never a guess. */
    @Test
    void readsProfileChatterWithNoAnswerAsUnknown() {
        assertThat(ClaudeSignIn.readStatus("running hook {start}\ndone {ok}\n"))
            .isEqualTo(ClaudeSignInState.UNKNOWN);
    }

    // --- Which account a machine is signed in as ----------------------------------------------------

    /**
     * Signing the fleet into the wrong account is otherwise invisible until something fails, so the
     * account is read back and shown. It is an observation that rides the status response — never stored,
     * and it carries no credential material because {@code auth status} emits none.
     */
    @Test
    void readsWhichAccountAMachineIsSignedInAs() {
        assertThat(ClaudeSignIn.readAccount(statusJson(true))).contains(
            new ClaudeAccount("operator@example.com", "Example Org", "max"));
    }

    @Test
    void readsNoAccountFromAMachineThatIsNotSignedIn() {
        assertThat(ClaudeSignIn.readAccount(statusJson(false))).isEmpty();
        assertThat(ClaudeSignIn.readAccount(ClaudeSignInOutput.CLI_ABSENT_MARKER)).isEmpty();
        assertThat(ClaudeSignIn.readAccount(null)).isEmpty();
        assertThat(ClaudeSignIn.readAccount("{\"loggedIn\": tr")).isEmpty();
    }

    /** A signed-in machine that reports no email still reads as signed in, just without an account. */
    @Test
    void readsNoAccountWhenTheCliOmitsIt() {
        assertThat(ClaudeSignIn.readStatus("{\"loggedIn\": true}")).isEqualTo(ClaudeSignInState.SIGNED_IN);
        assertThat(ClaudeSignIn.readAccount("{\"loggedIn\": true}")).isEmpty();
    }

    // --- Whether a sign-in is worth starting at all -------------------------------------------------

    /**
     * Verified live on Claude Code 2.1.251: {@code claude auth login --claudeai} on an already-signed-in
     * machine prints a fresh authorization URL and waits at its prompt, exactly as it does on a signed-out
     * one. So re-signing in — to move a machine onto a different account, or to replace a credential that
     * has gone bad — simply works, and nothing is refused for it.
     */
    @Test
    void letsAnAlreadySignedInMachineSignInAgain() {
        assertThatCode(() -> ClaudeSignIn.requireSignInCanBegin(ClaudeSignInState.SIGNED_IN))
            .doesNotThrowAnyException();
    }

    @Test
    void refusesToBeginOnAMachineWithNoClaudeInstalled() {
        assertThatThrownBy(() -> ClaudeSignIn.requireSignInCanBegin(ClaudeSignInState.NOT_INSTALLED))
            .isInstanceOf(ClaudeSignInFailedException.class)
            .hasMessageContaining("not installed");
    }

    @Test
    void beginsOnAnyOtherState() {
        assertThatCode(() -> {
            ClaudeSignIn.requireSignInCanBegin(ClaudeSignInState.SIGNED_OUT);
            ClaudeSignIn.requireSignInCanBegin(ClaudeSignInState.UNKNOWN);
            ClaudeSignIn.requireSignInCanBegin(ClaudeSignInState.UNREACHABLE);
        }).doesNotThrowAnyException();
    }

    // --- Every operator-facing sentence a sign-in can produce, in one place -------------------------

    /**
     * The roster exists to be auditable: if some of these sentences were built in the relay and some
     * here, the check below would only be looking at half of them. So every one a sign-in can produce is
     * a factory on this class, and this test walks the whole roster.
     */
    @Test
    void noSignInSentenceEverCarriesAUrlACodeOrAToken() {
        assertThat(List.of(
            ClaudeSignIn.notInstalled().getMessage(),
            ClaudeSignIn.exitedBeforeShowingUrl().getMessage(),
            ClaudeSignIn.couldNotReadAuthorizationUrl(Duration.ofSeconds(45)).getMessage(),
            ClaudeSignIn.couldNotBeStarted().getMessage(),
            ClaudeSignIn.interruptedWaitingForUrl().getMessage(),
            ClaudeSignIn.signInNotLive().getMessage(),
            ClaudeSignIn.noSignInWaiting().getMessage()))
            .allSatisfy(message -> assertThat(message.toLowerCase(Locale.ROOT))
                .doesNotContain("http").doesNotContain("token").doesNotContain("oauth")
                .isNotBlank());
    }

    @Test
    void saysHowToGetASignInWaitingWhenThereIsNone() {
        assertThat(ClaudeSignIn.noSignInWaiting())
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("sign-in")
            .hasMessageContaining("start");
    }

    // --- How long a sign-in is given ----------------------------------------------------------------

    @Test
    void treatsASignInNobodyCameBackToAsAbandoned() {
        Instant now = Instant.parse("2026-08-31T12:00:00Z");

        assertThat(ClaudeSignIn.isAbandoned(now.minus(Duration.ofMinutes(16)), now)).isTrue();
        assertThat(ClaudeSignIn.isAbandoned(now.minus(Duration.ofMinutes(1)), now)).isFalse();
        assertThat(ClaudeSignIn.isAbandoned(now, now)).isFalse();
    }

    @Test
    void givesTheUrlAndTheCodeBoundedWaits() {
        assertThat(ClaudeSignIn.URL_WAIT).isBetween(Duration.ofSeconds(20), Duration.ofMinutes(2));
        assertThat(ClaudeSignIn.CODE_WAIT).isBetween(Duration.ofSeconds(10), Duration.ofMinutes(2));
    }

    /** The real shape, pretty-printed across a dozen lines exactly as the CLI emits it. */
    private static String statusJson(boolean loggedIn) {
        return """
            {
              "loggedIn": %s,
              "authMethod": "claude.ai",
              "apiProvider": "firstParty",
              "analyticsDisabled": false,
              "projectsDirectory": "/home/ubuntu/.claude/projects",
              "email": "operator@example.com",
              "orgId": "org-abc123",
              "orgName": "Example Org",
              "subscriptionType": "max"
            }""".formatted(loggedIn);
    }

    // --- Where the CLI actually lives -----------------------------------------------------------------

    /**
     * <b>The defect this pins cost a live-wrong readout.</b> Claude Code installs itself to
     * {@code ~/.local/bin} by default, and that directory reaches PATH only through {@code ~/.profile},
     * which a <em>login</em> shell sources. An SSH exec channel — what every command here travels down —
     * gets a non-login, non-interactive shell, whose PATH is the bare system one. So the PATH check
     * declared a perfectly ordinary installation missing, and Vaier reported {@code NOT_INSTALLED} for
     * most of the fleet while silently withholding the Sign in button.
     *
     * <p>A login shell is the fix rather than prepending {@code $HOME/.local/bin}, because it applies
     * whatever that machine's own profile sets up — npm-global, a version manager, anything — instead of
     * Vaier guessing at one layout and getting the next one wrong the same way.
     */
    @Test
    void everyCommandRunsInALoginShellSoAUserInstalledCliIsFound() {
        assertThat(List.of(ClaudeSignIn.startCommand(), ClaudeSignIn.statusCommand(),
            ClaudeSignIn.signOutCommand()))
            .allSatisfy(command -> assertThat(command).contains("bash -lc").contains("sh -lc"));
    }

    /**
     * The PATH check has to run <em>inside</em> the login shell. Outside it, the guard answers about the
     * wrong PATH and reports the CLI absent before the login shell it would have been found in ever
     * starts.
     */
    @Test
    void thePathCheckItselfRunsInsideTheLoginShell() {
        assertThat(List.of(ClaudeSignIn.startCommand(), ClaudeSignIn.statusCommand(),
            ClaudeSignIn.signOutCommand()))
            .allSatisfy(command -> {
                assertThat(command).contains("bash -lc").contains("command -v claude");
                assertThat(command.indexOf("bash -lc"))
                    .as("the login shell must be entered before the PATH check runs")
                    .isLessThan(command.indexOf("command -v claude"));
            });
    }

    /**
     * The regression, reproduced for real rather than asserted about. A throwaway HOME is built with the
     * exact shape of the live defect — a {@code claude} that exists only in {@code ~/.local/bin}, put on
     * PATH only by {@code ~/.profile} — and the status command is run through a <em>non-login,
     * non-interactive</em> shell, which is precisely what an SSH exec channel gives it.
     *
     * <p>Before the fix this produced {@link ClaudeSignInState#NOT_INSTALLED}. Nothing about the command
     * string is inspected here: it is executed, and the answer has to be right.
     */
    @Test
    void findsACliThatOnlyALoginShellsPathReaches() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "needs bash");
        Path home = Files.createTempDirectory("vaier-claude-signin-home");
        try {
            Path bin = Files.createDirectories(home.resolve(".local/bin"));
            Path fakeCli = bin.resolve("claude");
            Files.writeString(fakeCli, "#!/bin/sh\necho '{\"loggedIn\": true, \"email\": \"o@e.com\"}'\n");
            fakeCli.toFile().setExecutable(true);
            // The whole defect in one line: ~/.local/bin reaches PATH only through the profile.
            Files.writeString(home.resolve(".profile"), "PATH=\"$HOME/.local/bin:$PATH\"\n");

            String output = runInANonLoginShell(ClaudeSignIn.statusCommand(), home);

            assertThat(ClaudeSignIn.readStatus(output)).isEqualTo(ClaudeSignInState.SIGNED_IN);
        } finally {
            deleteRecursively(home);
        }
    }

    /** A machine that genuinely has no Claude anywhere still reports it, login shell or not. */
    @Test
    void stillReportsNotInstalledWhenThereIsGenuinelyNoCli() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "needs bash");
        Path home = Files.createTempDirectory("vaier-claude-signin-empty");
        try {
            Files.writeString(home.resolve(".profile"), "PATH=\"$HOME/.local/bin:$PATH\"\n");

            String output = runInANonLoginShell(ClaudeSignIn.statusCommand(), home);

            assertThat(ClaudeSignIn.readStatus(output)).isEqualTo(ClaudeSignInState.NOT_INSTALLED);
        } finally {
            deleteRecursively(home);
        }
    }

    /**
     * Runs {@code command} the way an SSH exec channel does — a non-login, non-interactive shell with a
     * bare system PATH — inside a throwaway {@code home}.
     */
    private static String runInANonLoginShell(String command, Path home) throws Exception {
        Process process = new ProcessBuilder("/usr/bin/env", "-i", "HOME=" + home, "USER=vaier",
            "PATH=/usr/local/bin:/usr/bin:/bin", "/bin/sh", "-c", command)
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor(30, TimeUnit.SECONDS);
        return output;
    }

    private static void deleteRecursively(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        }
    }
}
