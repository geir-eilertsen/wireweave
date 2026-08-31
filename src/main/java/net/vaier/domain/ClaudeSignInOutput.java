package net.vaier.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads meaning out of what the unmodified Claude CLI printed into the PTY during a
 * {@link ClaudeSignIn}. Screen-scraping a program Vaier does not own is the single fragile part of the
 * whole feature, so it is contained here — one pure, total function per question — rather than smeared
 * across a service as a regex nobody dares touch. It is a decision about what output <em>means</em>,
 * which is why it is domain and not adapter.
 *
 * <p><b>Nothing here holds state.</b> The capture it is handed contains the authorization URL and, later,
 * the operator's pasted code echoed back by the PTY. Both are Anthropic's to hold, not Vaier's: this
 * class reads a string it is given, answers, and keeps nothing — no field, no cache, no {@code toString}
 * that could carry either into a log line. Same shape as {@link PasswordPrompt} and
 * {@link PersistentShell}, for the same reason.
 *
 * <p><b>Why the URL is read from the escape codes and not the visible text.</b> The CLI prints the URL as
 * an <a href="https://gist.github.com/egmontkob/eb114294efbcd5adb1944c9f3cb5feda">OSC 8</a> hyperlink:
 * {@code ESC ] 8 ; ; <url> ESC \ <label> ESC ] 8 ; ; ESC \}. The label is the same URL again, but the TUI
 * lays it out itself and breaks it across terminal rows with box-drawing padding between the pieces —
 * and it redraws the whole block several times as the screen repaints. The hyperlink <em>parameter</em>
 * is the only place the URL survives in one contiguous piece, so it is tried first; the stripped visible
 * text is the fallback for a CLI that stops emitting hyperlinks.
 *
 * <p><b>The URL is matched structurally.</b> Never by client id, never by state, never by the exact host
 * path — those are Anthropic's to change, and a match that depended on them would break silently on the
 * next CLI release while still looking like it worked.
 */
public final class ClaudeSignInOutput {

    /**
     * Echoed by {@link ClaudeSignIn#startCommand()} when the machine has no {@code claude} on its PATH.
     * Chosen to be a string no ordinary shell, MOTD or CLI banner emits, so it can never be misread.
     */
    public static final String CLI_ABSENT_MARKER = "VAIER-CLAUDE-CLI-ABSENT";

    /**
     * Anthropic's authorization URL, matched by shape: HTTPS, an Anthropic-operated host, an
     * {@code .../oauth/authorize} path, and a query string. The terminating class excludes whitespace,
     * quotes, and the escape/bell bytes that end an OSC sequence, so a match can never run off the end of
     * the URL into the surrounding redraw.
     */
    private static final Pattern AUTHORIZATION_URL = Pattern.compile(
        "https://[A-Za-z0-9.-]*(?:claude\\.ai|claude\\.com|anthropic\\.com)"
            + "/[^\\s\"'<>\\\\\\x07\\x1b]*oauth/authorize\\?[^\\s\"'<>\\\\\\x07\\x1b]+");

    /** An OSC 8 hyperlink introducer; group 1 is the URL parameter, ended by BEL or ST ({@code ESC \}). */
    private static final Pattern OSC8_HYPERLINK = Pattern.compile(
        "\\x1b]8;[^;\\x07\\x1b]*;([^\\x07\\x1b]*)(?:\\x07|\\x1b\\\\)");

    /** Operating System Command sequences — stripped whole, params and all. */
    private static final Pattern OSC_SEQUENCE = Pattern.compile("\\x1b][^\\x07\\x1b]*(?:\\x07|\\x1b\\\\)?");

    /** Control Sequence Introducer sequences — colours, cursor moves, the TUI's whole repaint vocabulary. */
    private static final Pattern CSI_SEQUENCE = Pattern.compile("\\x1b\\[[0-?]*[ -/]*[@-~]");

    /** The remaining two-byte escapes (charset selects, ST, RI, …). */
    private static final Pattern SHORT_ESCAPE = Pattern.compile("\\x1b[@-Z\\\\-_]?");

    /** Trailing characters a terminal redraw can leave glued to a URL that are never part of one. */
    private static final Pattern URL_TRAILING_NOISE = Pattern.compile("[.,;:'\")\\]}»]+$");

    /** Phrases that mean the sign-in landed. Matched on the stripped, lower-cased text. */
    private static final Pattern SUCCEEDED = Pattern.compile(
        "login successful|logged in as|successfully logged in|authentication successful"
            + "|you are now logged in|sign.?in successful");

    /** Phrases that mean it did not. Matched only after {@link #SUCCEEDED} has been ruled out. */
    private static final Pattern FAILED = Pattern.compile(
        "invalid code|invalid authorization|authentication failed|authorization failed"
            + "|login failed|sign.?in failed|oauth error|could not (?:complete|verify) (?:the )?login");

    private ClaudeSignInOutput() {
    }

    /**
     * The authorization URL the CLI is waiting for the operator to open, or empty when the capture does
     * not contain one yet. Total: null, empty, half-written escape sequences and pure spinner frames all
     * answer empty rather than throwing — this runs on every chunk of PTY output, and a parser that threw
     * would take the sign-in down with it.
     *
     * <p>The URL is Anthropic's, handed straight back to the operator's browser. Vaier neither stores it
     * nor logs it.
     */
    public static Optional<String> readAuthorizationUrl(String captured) {
        if (captured == null || captured.isEmpty()) {
            return Optional.empty();
        }
        // The hyperlink parameter first: it is the one copy the TUI has not wrapped or repainted.
        Matcher hyperlink = OSC8_HYPERLINK.matcher(captured);
        while (hyperlink.find()) {
            Optional<String> url = authorizationUrlIn(hyperlink.group(1));
            if (url.isPresent()) {
                return url;
            }
        }
        return authorizationUrlIn(stripEscapes(captured));
    }

    /**
     * What the CLI says about a sign-in it has been given a code for. A hint only — see
     * {@link ClaudeSignInOutcome} — and never optimistic: anything that is not an explicit success or an
     * explicit failure reads as {@link ClaudeSignInOutcome#PENDING}.
     */
    public static ClaudeSignInOutcome readOutcome(String captured) {
        if (captured == null || captured.isEmpty()) {
            return ClaudeSignInOutcome.PENDING;
        }
        String text = lowerStripped(captured);
        if (SUCCEEDED.matcher(text).find()) {
            return ClaudeSignInOutcome.SUCCEEDED;
        }
        if (FAILED.matcher(text).find()) {
            return ClaudeSignInOutcome.FAILED;
        }
        return ClaudeSignInOutcome.PENDING;
    }

    /** Whether the start command reported that this machine has no {@code claude} installed at all. */
    public static boolean reportsCliAbsent(String captured) {
        return captured != null && stripEscapes(captured).contains(CLI_ABSENT_MARKER);
    }

    /** The first authorization-shaped URL in {@code text}, with any redraw punctuation trimmed off. */
    private static Optional<String> authorizationUrlIn(String text) {
        Matcher url = AUTHORIZATION_URL.matcher(text);
        if (!url.find()) {
            return Optional.empty();
        }
        String trimmed = URL_TRAILING_NOISE.matcher(url.group()).replaceAll("");
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static String lowerStripped(String captured) {
        return stripEscapes(captured).toLowerCase(Locale.ROOT);
    }

    /**
     * The capture with its terminal escape sequences removed, leaving roughly what a human saw. OSC goes
     * first because an OSC parameter may itself contain a {@code [}, which a CSI-first pass would mangle.
     */
    private static String stripEscapes(String captured) {
        String text = OSC_SEQUENCE.matcher(captured).replaceAll("");
        text = CSI_SEQUENCE.matcher(text).replaceAll("");
        return SHORT_ESCAPE.matcher(text).replaceAll("");
    }
}
