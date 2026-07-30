package net.vaier.domain;

import java.util.List;

/**
 * The block decisions {@link BreachAttemptTracker} found newly appeared in one sweep, and how
 * that reads as an email to admins (#329 Slice 2).
 *
 * <p><b>One sweep, one mail.</b> A burst of decisions from one attacker is one message listing
 * all of them, not one message per decision — the sibling of {@link ImageUpdateRollup}'s own
 * rollup reasoning. It renders itself; {@code NotificationService} only sequences the send.
 *
 * <p>The mail says what CrowdSec did and stops — no unban affordance here (that's Slice 3).
 *
 * @param decisions the newly-appeared block decisions, in the order they should be listed
 */
public record BreachAttemptRollup(List<BlockDecision> decisions) {

    /** Whether there is anything to say. A sweep that found nothing new sends no mail at all. */
    public boolean worthSending() {
        return decisions != null && !decisions.isEmpty();
    }

    /** Subject line — the one decision, or a count when there are several. */
    public String subject() {
        if (decisions.size() == 1) {
            return "[Vaier] Breach attempt: " + decisions.get(0).label();
        }
        return "[Vaier] Breach attempt: " + decisions.size() + " new block decisions";
    }

    /**
     * Body: every newly-appeared decision, then the one thing the operator needs to know — it's
     * already refused at the edge. {@code baseDomain} builds the Vaier UI link, omitted when null
     * or blank.
     */
    public String body(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append(decisions.size() == 1
            ? "CrowdSec blocked a new attempt to break in:\n\n"
            : "CrowdSec blocked new attempts to break in:\n\n");
        for (BlockDecision decision : decisions) {
            body.append("  • ").append(decision.label()).append("\n");
        }
        body.append("\nEach source above is refused at the edge before it reaches oauth2-proxy or a published service.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
