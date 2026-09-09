package net.vaier.domain;

import java.util.Optional;

/**
 * May <b>Ask</b> be offered at all? One decision, in one place (#360): it is available exactly while an
 * <b>Anthropic API key</b> is stored, and unavailable otherwise. The menu asks it to decide whether to show
 * the pane, and the service asks it again before every question — the operator may have cleared the key in
 * between.
 */
public record AskAvailability(boolean available) {

    /** The sentence the operator is answered with when there is no key; it names the fix. */
    private static final String NO_KEY =
        "Ask needs an Anthropic API key. Add one in Settings and Vaier can answer questions about your fleet.";

    /** Empty configuration is a Vaier that has never been set up, and it holds no key either. */
    public static AskAvailability of(Optional<VaierConfig> config) {
        return new AskAvailability(config.map(VaierConfig::hasAnthropicApiKey).orElse(false));
    }

    /** Passes quietly when Ask may be offered; otherwise refuses with the sentence above. */
    public void requireAvailable() {
        if (!available) {
            throw new AskUnavailableException(NO_KEY);
        }
    }
}
