package net.vaier.domain;

/**
 * <b>Ask</b> was reached for while no <b>Anthropic API key</b> is stored (#360). A state conflict, not a
 * fault: nothing is broken, the one thing Ask needs simply is not there yet. Mapped to {@code 409} beside
 * {@link ConflictException}, carrying a sentence that names the fix.
 */
public class AskUnavailableException extends RuntimeException {
    public AskUnavailableException(String message) {
        super(message);
    }
}
