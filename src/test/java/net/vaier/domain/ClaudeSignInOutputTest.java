package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one fragile part of a Claude sign-in: reading Anthropic's own authorization URL back out of an
 * unmodified CLI's terminal output. These fixtures are shaped like the real capture — OSC 8 hyperlink
 * escapes wrapping a very long URL, redrawn several times as the TUI repaints, soft-wrapped across rows.
 */
class ClaudeSignInOutputTest {

    private static final String ESC = "\033";

    private static final String URL = "https://claude.com/cai/oauth/authorize"
        + "?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e&response_type=code"
        + "&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback"
        + "&scope=org%3Acreate_api_key+user%3Aprofile+user%3Ainference"
        + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"
        + "&state=Xk7pQm2ZrL9vN4bT1cY8wA";

    /** An OSC 8 hyperlink: the params carry the URL whole, the visible label repeats it soft-wrapped. */
    private static String hyperlinked(String url) {
        return ESC + "]8;;" + url + ESC + "\\" + wrapped(url) + ESC + "]8;;" + ESC + "\\";
    }

    /** The TUI's own wrapping: the visible label broken across terminal rows, inside a drawn box. */
    private static String wrapped(String url) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < url.length(); i += 60) {
            out.append(ESC).append("[2m│").append(ESC).append("[0m ")
                .append(url, i, Math.min(url.length(), i + 60)).append("\r\n");
        }
        return out.toString();
    }

    @Test
    void readsTheAuthorizationUrlOutOfAnOsc8Hyperlink() {
        String capture = ESC + "[?25l" + ESC + "[2J" + "Browser didn't open? Use the url below:\r\n"
            + hyperlinked(URL) + "\r\nPaste code here if prompted > ";

        assertThat(ClaudeSignInOutput.readAuthorizationUrl(capture)).contains(URL);
    }

    @Test
    void readsTheUrlWhenTheCliPrintsItAsPlainTextWithNoHyperlink() {
        String capture = ESC + "[1mSign in" + ESC + "[0m\r\n" + URL + "\r\n";

        assertThat(ClaudeSignInOutput.readAuthorizationUrl(capture)).contains(URL);
    }

    @Test
    void survivesTheTuiRedrawingTheSameUrlSeveralTimes() {
        String capture = hyperlinked(URL) + ESC + "[3A" + hyperlinked(URL) + ESC + "[3A" + hyperlinked(URL);

        assertThat(ClaudeSignInOutput.readAuthorizationUrl(capture)).contains(URL);
    }

    /**
     * Nothing about the URL may be hard-coded: a different client, a different code challenge and a
     * different state are the same URL as far as Vaier is concerned. It is matched structurally.
     */
    @Test
    void matchesTheUrlStructurallyRatherThanByClientIdOrState() {
        String other = "https://claude.ai/oauth/authorize?client_id=totally-different"
            + "&code=true&code_challenge=zzz&state=qqq";

        assertThat(ClaudeSignInOutput.readAuthorizationUrl(hyperlinked(other))).contains(other);
    }

    @Test
    void findsNoUrlInASpinnerThatNeverGotThere() {
        String capture = ESC + "[?25l" + " ⠻ Starting…\r" + " ⠹ Starting…\r"
            + " ⠹ Starting…\r";

        assertThat(ClaudeSignInOutput.readAuthorizationUrl(capture)).isEmpty();
    }

    @Test
    void findsNoUrlInNullOrEmptyOutput() {
        assertThat(ClaudeSignInOutput.readAuthorizationUrl(null)).isEmpty();
        assertThat(ClaudeSignInOutput.readAuthorizationUrl("")).isEmpty();
    }

    /** An unrelated https link in the banner must never be mistaken for the sign-in URL. */
    @Test
    void ignoresLinksThatAreNotAnAuthorizationUrl() {
        String capture = hyperlinked("https://docs.claude.com/en/docs/claude-code")
            + "\r\nhttps://github.com/anthropics/claude-code/issues\r\n";

        assertThat(ClaudeSignInOutput.readAuthorizationUrl(capture)).isEmpty();
    }

    @Test
    void readsASuccessfulSignInFromTheClisOwnWords() {
        assertThat(ClaudeSignInOutput.readOutcome("\r\n" + ESC + "[32mLogin successful" + ESC + "[0m\r\n"))
            .isEqualTo(ClaudeSignInOutcome.SUCCEEDED);
        assertThat(ClaudeSignInOutput.readOutcome("Logged in as geir@example.com"))
            .isEqualTo(ClaudeSignInOutcome.SUCCEEDED);
    }

    @Test
    void readsAFailedSignInFromTheClisOwnWords() {
        assertThat(ClaudeSignInOutput.readOutcome("Invalid code. Please try again."))
            .isEqualTo(ClaudeSignInOutcome.FAILED);
        assertThat(ClaudeSignInOutput.readOutcome("OAuth error: authorization failed"))
            .isEqualTo(ClaudeSignInOutcome.FAILED);
    }

    /** Never optimistic: output that says nothing either way is still pending, never a success. */
    @Test
    void readsAnythingElseAsStillPending() {
        assertThat(ClaudeSignInOutput.readOutcome(" ⠻ Exchanging code…"))
            .isEqualTo(ClaudeSignInOutcome.PENDING);
        assertThat(ClaudeSignInOutput.readOutcome(null)).isEqualTo(ClaudeSignInOutcome.PENDING);
        assertThat(ClaudeSignInOutput.readOutcome("")).isEqualTo(ClaudeSignInOutcome.PENDING);
    }

    @Test
    void reportsWhenTheMachineHasNoClaudeInstalled() {
        assertThat(ClaudeSignInOutput.reportsCliAbsent(
            ClaudeSignInOutput.CLI_ABSENT_MARKER + "\r\n")).isTrue();
        assertThat(ClaudeSignInOutput.reportsCliAbsent("Paste code here if prompted >")).isFalse();
        assertThat(ClaudeSignInOutput.reportsCliAbsent(null)).isFalse();
    }

    /** Sanity: the readers are total — no input, however hostile, makes one throw. */
    @Test
    void neverThrowsOnGarbage() {
        String garbage = ESC + "]8;;" + ESC + "[" + " " + ESC + "]" + ESC;
        Optional<String> url = ClaudeSignInOutput.readAuthorizationUrl(garbage);
        assertThat(url).isEmpty();
        assertThat(ClaudeSignInOutput.readOutcome(garbage)).isEqualTo(ClaudeSignInOutcome.PENDING);
    }
}
