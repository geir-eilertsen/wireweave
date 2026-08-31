package net.vaier.domain;

/**
 * What the Claude CLI's own output says about a sign-in that has been given its
 * {@link ClaudeSignIn authorization code} — as read by
 * {@link ClaudeSignInOutput#readOutcome(String)}.
 *
 * <p>Deliberately never optimistic. {@link #PENDING} is the answer whenever the output says nothing
 * either way, and it is the answer for garbage: a screen-scrape that guessed "succeeded" would let Vaier
 * tell an operator a machine is signed in when it is not. It is only ever a <em>hint</em> that lets the
 * relay stop waiting early — the authoritative answer is always the CLI's own
 * {@code auth status}, read back through {@link ClaudeSignIn#statusCommand()}.
 */
public enum ClaudeSignInOutcome {

    /** The CLI has not said anything conclusive yet. */
    PENDING,

    /** The CLI said the sign-in worked. */
    SUCCEEDED,

    /** The CLI said the sign-in did not work — a bad code, a rejected authorization. */
    FAILED
}
