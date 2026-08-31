package net.vaier.domain;

/**
 * Where one machine stands on <b>Claude sign-in</b>: whether the unmodified Claude CLI on it is signed
 * in to the operator's own Anthropic account.
 *
 * <p>Read from the machine's own answer to {@link ClaudeSignIn#statusCommand()} — the CLI's own
 * {@code claude auth status --json}, which reports its auth state and no credential material at all.
 *
 * <p>Four of these six are ordinary, non-alarming facts rather than failures. A machine with no Claude
 * installed is not broken; a phone has nowhere to run one; a machine that is asleep has not answered
 * yet. Only {@link #UNKNOWN} is a machine that answered with something Vaier could not read, and even
 * that is reported rather than thrown: an odd answer is still a standing worth showing.
 */
public enum ClaudeSignInState {

    /** The CLI is installed and holds its own credential: this machine is signed in. */
    SIGNED_IN,

    /** The CLI is installed and holds no credential: this machine is not signed in yet. */
    SIGNED_OUT,

    /** No {@code claude} on this machine's PATH. A plain fact, not a problem. */
    NOT_INSTALLED,

    /** The machine did not answer — asleep, moved, or refusing the connection. */
    UNREACHABLE,

    /** Not a machine Vaier can open a shell on at all, so not a place a sign-in could happen. */
    SKIPPED,

    /** The machine answered, but with nothing Vaier could read. Never assumed to be signed in. */
    UNKNOWN
}
