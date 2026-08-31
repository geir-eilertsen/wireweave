package net.vaier.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * <b>Claude sign-in</b> — signing a machine's unmodified Claude Code CLI in to the operator's own
 * Anthropic account, with Vaier orchestrating and the credential never passing through it.
 *
 * <p><b>Why it is built this way, and why that is not negotiable.</b> Anthropic's terms forbid a third
 * party collecting, storing or intermediating Claude credentials or session tokens: sign-in must
 * complete through Anthropic's own flow. What is expressly allowed is an end user signing in to the
 * <em>unmodified</em> binary with their own subscription. So Vaier does exactly four things — it starts
 * the real {@code claude} on the target machine, relays Anthropic's authorization URL out to the
 * operator's browser, relays the code Anthropic showed them back into the waiting process, and asks the
 * machine afterwards whether it ended up signed in. The URL, the code and the resulting token are
 * transient and in-memory for the life of one sign-in. <b>None of them is ever written to disk, to a
 * store, or to a log line.</b> There is deliberately no persistence port anywhere in this feature; if
 * one appears, something has gone wrong.
 *
 * <p><b>Everything goes through the CLI's own {@code claude auth} subcommands</b> — {@code auth login
 * --claudeai} to start, {@code auth logout} to end, {@code auth status --json} to ask. That is the whole
 * design. Anthropic built these to be driven, so Vaier depends on a documented interface rather than on
 * the startup banner of a REPL and the mode bits of a file on disk. Two things fall out of it that matter
 * more than the tidiness: the status answer is <em>authoritative</em> rather than inferred, and it is
 * structured JSON that contains no credential material at all — no token, no key, no session — so asking
 * the question is something Vaier is plainly allowed to do. Signing out is the CLI releasing its own
 * credential, never Vaier deleting one.
 *
 * <p>This class is the shell half of that — a pure, IO-free builder of the exact strings sent over SSH,
 * plus the tiny parsers that read a machine's answer back into a domain decision, the same house pattern
 * as {@link PersistentShell} and {@link Archive}'s reading of borg's JSON. Reading the CLI's
 * <em>terminal</em> output during the login itself is the other half and lives in
 * {@link ClaudeSignInOutput}.
 *
 * <p><b>The CLI needs a pty and has to outlive the request that started it.</b> Without a pty
 * {@code claude} exits immediately and prints nothing at all. And the operator has to leave Vaier,
 * approve in their own browser, and come back with a code — two separate HTTP requests, with a process
 * waiting at a prompt in between. Both are already solved: the sign-in runs inside a
 * {@link PersistentShell persistent shell}, which supplies the pty and keeps the process alive across
 * the gap and across a Vaier restart. It gets its own reserved session name, so a sign-in can never land
 * in a pane the operator is using.
 */
public final class ClaudeSignIn {

    /** The pane name whose {@link PersistentShell} session a sign-in reserves for itself. */
    private static final String PANE = "claude-sign-in";

    /** Shared, thread-safe reader for the CLI's JSON answer — the same shape {@link Archive} uses. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The charset an authorization code is confined to. Anthropic's codes are URL-safe text — base64url
     * with a {@code #} separating the state — and the one thing that must never happen is the operator's
     * paste being read as a second shell command: if the CLI has already exited, whatever is written
     * lands in the shell that started it. So every shell metacharacter is out, {@code $ ( ) ; ' " & | `}
     * included. Rejecting up front beats quoting, because there is nothing legitimate outside this set to
     * quote.
     */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Za-z0-9._~:/#@%+=-]+");

    /**
     * How long the CLI is given to print Anthropic's authorization URL. Generous, because a cold CLI on a
     * sleepy NAS is slow — but bounded, because the alternative is an operator watching a spinner that
     * will never resolve. How long a sign-in is worth waiting on is a judgement about sign-ins, not about
     * HTTP, so it is taken here rather than in whatever happens to be driving one.
     */
    public static final Duration URL_WAIT = Duration.ofSeconds(45);

    /** How long the CLI is given to react to a pasted code before the machine itself is asked. */
    public static final Duration CODE_WAIT = Duration.ofSeconds(30);

    /**
     * How long a sign-in may sit at its prompt before it counts as abandoned rather than patient. The CLI
     * waits by design — that is what lets the operator leave and come back — so nothing else would ever
     * end one an operator walked away from. A quarter of an hour is long enough to read Anthropic's page
     * and short enough that a forgotten dialog is not still holding a session at bedtime.
     */
    private static final Duration ABANDONED_AFTER = Duration.ofMinutes(15);

    /** The CLI's own sign-in subcommand. {@code --claudeai} is the subscription flow, not Console billing. */
    private static final String LOGIN = "claude auth login --claudeai";

    /** The CLI's own sign-out subcommand: it releases its credential, Vaier never touches one. */
    private static final String LOGOUT = "claude auth logout";

    /** The CLI's own status question. {@code --json} is its default; stated so a default change cannot bite. */
    private static final String STATUS = "claude auth status --json";

    private ClaudeSignIn() {
    }

    /** Whether a sign-in started at {@code startedAt} has been left unfinished long enough to end. */
    public static boolean isAbandoned(Instant startedAt, Instant now) {
        return startedAt.isBefore(now.minus(ABANDONED_AFTER));
    }

    /** The reserved tmux session a sign-in runs in — {@code vaier-claude-sign-in}. */
    public static String sessionName() {
        return PersistentShell.sessionName(PANE);
    }

    /**
     * The command run in the PTY to begin a sign-in: {@code claude auth login --claudeai}, unmodified and
     * off the machine's PATH, inside its reserved persistent shell so it survives between the request
     * that starts it and the request that hands it the code.
     *
     * <p>{@code --claudeai} is the Claude subscription flow (the CLI's own default, stated here rather
     * than assumed) as opposed to {@code --console}, which bills API usage. The operator is signing in a
     * subscription, so it is named explicitly — a default that changed underneath Vaier would otherwise
     * silently start signing the fleet into the wrong kind of account.
     *
     * <p>The PATH check comes first and reports {@link ClaudeSignInOutput#CLI_ABSENT_MARKER} rather than
     * letting the operator sit on a spinner until a timeout: a machine with no Claude on it is a plain
     * fact Vaier can state in the first second, and stating it is cheaper than discovering it. tmux's
     * status bar is turned off for this session only, exactly as the web terminal does.
     */
    public static String startCommand() {
        String name = singleQuote(sessionName());
        // The command tmux runs gets its own login shell as well. tmux's shell-command is spawned by a
        // fresh non-login `sh`, and `new-session -A` may attach to a tmux *server* started long ago from
        // some other environment — so the PATH this session ends up with cannot be inherited on trust.
        return inLoginShell(cliGuard()
            + "if command -v tmux >/dev/null 2>&1; then "
            + "exec tmux new-session -A -D -s " + name + " " + singleQuote(inLoginShell(LOGIN))
            + " \\; set-option -t " + name + " status off; "
            + "else exec " + LOGIN + "; fi");
    }

    /**
     * End the sign-in's persistent shell. Run whenever a sign-in finishes, fails or is abandoned — the
     * shell survives a dropped connection by design, so nothing else would ever stop it, and a
     * half-finished sign-in left waiting at a prompt is a process sitting on a machine forever.
     */
    public static String endCommand() {
        return PersistentShell.endCommand(PANE);
    }

    /**
     * Sign a machine out: {@code claude auth logout}, and nothing else.
     *
     * <p><b>Never by deleting the credential file.</b> Removing someone's credential from disk would work,
     * and it is exactly the line Vaier stays behind — deleting a credential is manipulating it. Vaier asks
     * the binary that owns the credential to let it go, and then asks the same binary whether it did.
     *
     * <p>A machine with no Claude on it reports {@link ClaudeSignInOutput#CLI_ABSENT_MARKER} and exits 0:
     * signing out a machine that was never signed in is success, not an error.
     */
    public static String signOutCommand() {
        return inLoginShell(cliGuard() + LOGOUT);
    }

    /**
     * The keystrokes that hand the CLI the code Anthropic showed the operator: the code itself and a
     * newline, nothing more. Whitespace from the paste is trimmed; anything outside
     * {@link #CODE_PATTERN} is refused rather than quoted.
     *
     * <p>The returned string is the code. It goes straight into the PTY and is never returned to a
     * caller that would store it, and never logged.
     *
     * @throws IllegalArgumentException the code is blank, or is not a plain authorization code
     */
    public static String keystrokesForCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("The authorization code must not be blank");
        }
        String trimmed = code.strip();
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                "That does not look like an authorization code — paste the code Anthropic showed you");
        }
        return trimmed + "\n";
    }

    /**
     * Refuse a sign-in that could accomplish nothing on a machine in {@code state}, before any session is
     * opened for it. Only one state qualifies: a machine with no CLI has nothing to start, and saying so
     * up front spares the operator a wait that could only ever end in a timeout.
     *
     * <p><b>An already-signed-in machine is not refused.</b> It used to be, because bare {@code claude}
     * on one opened an ordinary REPL and printed no URL. {@code claude auth login --claudeai} is an
     * explicit command and behaves the same either way — verified on Claude Code 2.1.251, where it prints
     * a fresh authorization URL on a signed-in machine and waits at its prompt. So re-signing in works:
     * moving a machine onto a different account, or replacing a credential that has gone bad, is just a
     * sign-in.
     *
     * <p>The wording is here rather than in the caller because it is the domain's judgement being
     * explained, and because every operator-facing sentence a sign-in can produce belongs in one place
     * where it can be checked for carrying nothing it shouldn't.
     */
    public static void requireSignInCanBegin(ClaudeSignInState state) {
        if (state == ClaudeSignInState.NOT_INSTALLED) {
            throw notInstalled();
        }
    }

    /** The machine has no {@code claude} to sign in. A plain fact with a plain next step. */
    public static ClaudeSignInFailedException notInstalled() {
        return new ClaudeSignInFailedException(
            "Claude Code is not installed on this machine — install it there first, then sign in.");
    }

    /**
     * The CLI never printed a URL Vaier could read within {@code waited}. The one failure this feature
     * has to get right: it names what could not be read, admits the CLI's output may have changed, and
     * offers the way round it that always works.
     */
    public static ClaudeSignInFailedException couldNotReadAuthorizationUrl(Duration waited) {
        return new ClaudeSignInFailedException(
            "Vaier could not read the login URL from the Claude CLI's output within "
                + waited.toSeconds() + " seconds — the CLI's output may have changed. Open a terminal on "
                + "this machine and run claude to sign in by hand; that always works.");
    }

    /** No sign-in is waiting on this machine, and the operator has to start one before pasting a code. */
    public static NotFoundException noSignInWaiting() {
        return new NotFoundException(
            "No Claude sign-in is waiting on this machine — start one and try again");
    }

    /** The sign-in could not be started, for a reason with nothing more specific to say about it. */
    public static ClaudeSignInFailedException couldNotBeStarted() {
        return new ClaudeSignInFailedException("The Claude sign-in could not be started.");
    }

    /** Vaier was shut down or interrupted while waiting for the URL. */
    public static ClaudeSignInFailedException interruptedWaitingForUrl() {
        return new ClaudeSignInFailedException("Interrupted while waiting for the login URL.");
    }

    /** The sign-in is recorded as waiting but has no live session behind it any more. */
    public static ClaudeSignInFailedException signInNotLive() {
        return new ClaudeSignInFailedException(
            "The Claude sign-in on this machine is no longer live — start it again.");
    }

    /** The CLI exited before it got as far as showing a URL. */
    public static ClaudeSignInFailedException exitedBeforeShowingUrl() {
        return new ClaudeSignInFailedException(
            "The Claude CLI on this machine exited before it showed a login URL. Open a terminal on that "
                + "machine and run claude to sign in by hand.");
    }

    /**
     * Ask a machine whether its Claude CLI is signed in — by asking the CLI, with
     * {@code claude auth status --json}.
     *
     * <p><b>It never goes near the credential file.</b> The CLI is the authority on its own auth state, so
     * Vaier asks it rather than inferring the answer from whether a file exists and what its mode bits
     * are. That is better on every axis — authoritative instead of guessed, structured instead of scraped,
     * and it survives the CLI moving or renaming its own storage — but the reason it is written this way
     * is simpler than any of those: the answer to this question contains no credential material, and the
     * file does.
     *
     * <p>The PATH guard comes first so "no Claude here" is an unambiguous marker rather than a shell error
     * to be pattern-matched. {@code stderr} is dropped: an older CLI without an {@code auth} subcommand
     * writes a usage error there, and the empty stdout that follows is read as
     * {@link ClaudeSignInState#UNKNOWN}, never as signed out.
     */
    public static String statusCommand() {
        return inLoginShell(cliGuard() + STATUS + " 2>/dev/null");
    }

    /**
     * Read a {@link #statusCommand()} answer into a state. Never optimistic and never throwing: a machine
     * that answered with a banner, a usage error, half a JSON object or nothing at all is
     * {@link ClaudeSignInState#UNKNOWN}, not a quiet success and not an exception — a machine that
     * answered oddly still has a standing worth showing, and throwing would replace it with nothing.
     *
     * <p><b>Unknown is not "no".</b> A machine Vaier could not ask must never read as
     * {@link ClaudeSignInState#SIGNED_OUT} — that would send an operator to re-run a sign-in that was
     * never needed, on evidence Vaier does not have.
     */
    public static ClaudeSignInState readStatus(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return ClaudeSignInState.UNKNOWN;
        }
        if (ClaudeSignInOutput.reportsCliAbsent(stdout)) {
            return ClaudeSignInState.NOT_INSTALLED;
        }
        return statusReport(stdout).map(ClaudeSignIn::loggedInState).orElse(ClaudeSignInState.UNKNOWN);
    }

    /** Whether a parsed report says signed in, signed out, or nothing Vaier is willing to act on. */
    private static ClaudeSignInState loggedInState(JsonNode report) {
        JsonNode loggedIn = report.get("loggedIn");
        if (loggedIn == null || !loggedIn.isBoolean()) {
            return ClaudeSignInState.UNKNOWN;
        }
        return loggedIn.booleanValue() ? ClaudeSignInState.SIGNED_IN : ClaudeSignInState.SIGNED_OUT;
    }

    /**
     * Which account a signed-in machine is signed in as, when it said. Empty for a machine that is not
     * signed in, could not be asked, or answered without an email — a standing with no account is still a
     * standing, and inventing one would be worse than showing none.
     */
    public static Optional<ClaudeAccount> readAccount(String stdout) {
        if (stdout == null || ClaudeSignInOutput.reportsCliAbsent(stdout)) {
            return Optional.empty();
        }
        return statusReport(stdout)
            .filter(report -> loggedInState(report) == ClaudeSignInState.SIGNED_IN)
            .map(report -> new ClaudeAccount(text(report, "email"), text(report, "orgName"),
                text(report, "subscriptionType")))
            .filter(account -> account.email() != null);
    }

    /**
     * The JSON object inside whatever the machine actually sent back.
     *
     * <p>There is usually something else in there. Every command runs in a login shell — it has to, or a
     * default-installed CLI is invisible — so the machine's profile gets to talk first, and an SSH
     * command can arrive wrapped in a banner or a shell warning besides. The CLI then pretty-prints its
     * answer across a dozen lines.
     *
     * <p>So the object is found by locating a <em>balanced</em> one and parsing it, trying each candidate
     * opening brace in turn. Bracketing the whole output from its first brace to its last would be
     * shorter and wrong: one {@code profile.d} script printing a brace, and the span covers the chatter
     * as well as the answer, parses as nothing, and the machine reads as unknown. Quoted strings are
     * respected while balancing, so a brace inside a value cannot end the object early. Never throws —
     * malformed, truncated and absent all answer empty.
     */
    private static Optional<JsonNode> statusReport(String stdout) {
        if (stdout == null) {
            return Optional.empty();
        }
        for (int start = stdout.indexOf('{'); start >= 0; start = stdout.indexOf('{', start + 1)) {
            int end = endOfObject(stdout, start);
            if (end < 0) {
                continue;
            }
            try {
                JsonNode parsed = MAPPER.readTree(stdout.substring(start, end + 1));
                if (parsed != null && parsed.isObject()) {
                    return Optional.of(parsed);
                }
            } catch (JsonProcessingException e) {
                // Not the object we are after — keep looking at later candidates.
            }
        }
        return Optional.empty();
    }

    /**
     * The index of the brace closing the object that opens at {@code start}, or -1 if it never closes.
     * Braces inside quoted strings are skipped, backslash escapes included, so a value containing one
     * cannot end the object early.
     */
    private static int endOfObject(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** A string field, or null when it is absent, null or not textual. */
    private static String text(JsonNode report, String field) {
        JsonNode value = report.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
            ? value.textValue() : null;
    }

    /**
     * Run {@code command} in a <b>login shell</b>. Every command this class builds goes through here, and
     * it is not a nicety — it is the difference between finding the CLI and declaring it missing.
     *
     * <p>Claude Code installs itself to {@code ~/.local/bin} by default, and on a stock Ubuntu that
     * directory reaches PATH only through {@code ~/.profile} — sourced by login shells. {@code ~/.bashrc}
     * is no help: it returns immediately when not interactive. An SSH exec channel, which is how every
     * command here travels, gets a shell that is <em>neither</em>, so its PATH is the bare system one and
     * an entirely ordinary installation looks absent. That is not a corner case, it is the default
     * install, and it had Vaier reporting {@code NOT_INSTALLED} across the fleet.
     *
     * <p><b>A login shell rather than prepending {@code $HOME/.local/bin}</b>, because it applies whatever
     * that machine's own profile sets up — an npm-global prefix, a version manager, a NAS's own layout —
     * instead of Vaier hardcoding the one arrangement it happened to look at and getting the next one
     * wrong in exactly the same way. {@code sh -lc} is the fallback for a machine with no bash.
     */
    private static String inLoginShell(String command) {
        String quoted = singleQuote(command);
        return "if command -v bash >/dev/null 2>&1; then exec bash -lc " + quoted
            + "; else exec sh -lc " + quoted + "; fi";
    }

    /**
     * The PATH check every {@code claude auth} command opens with — always <em>inside</em>
     * {@link #inLoginShell}, never around it, or it answers about the wrong PATH. A machine that has no
     * Claude on it says so in the first second with an unmistakable marker and exits 0, rather than
     * leaving the caller to interpret one shell's "command not found" against another's.
     */
    private static String cliGuard() {
        return "command -v claude >/dev/null 2>&1 || { echo " + ClaudeSignInOutput.CLI_ABSENT_MARKER
            + "; exit 0; }; ";
    }

    /** Single-quote for the shell, escaping any embedded quote with the {@code '\''} idiom. */
    private static String singleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
