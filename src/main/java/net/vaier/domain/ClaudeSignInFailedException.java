package net.vaier.domain;

/**
 * A {@link ClaudeSignIn} could not be carried out on a machine — Claude Code is not installed there, or
 * the CLI never printed an authorization URL Vaier could read.
 *
 * <p>The second case is the one this exception exists for. Reading a URL out of a program's terminal
 * output is screen-scraping, and screen-scraping breaks: the message must say plainly that Vaier could
 * not read the login URL, that the CLI's output may have changed, and that opening a terminal on the
 * machine and running {@code claude} by hand always works. Failing loudly with a way forward is the
 * whole contract here — a spinner that never resolves would be worse than an error.
 *
 * <p>Carries no URL, no code and no token. There is never anything from a sign-in worth putting in an
 * error message, and an exception message is one of the places a secret most easily reaches a log.
 */
public class ClaudeSignInFailedException extends RuntimeException {

    public ClaudeSignInFailedException(String message) {
        super(message);
    }
}
