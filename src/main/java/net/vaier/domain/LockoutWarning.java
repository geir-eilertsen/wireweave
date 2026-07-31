package net.vaier.domain;

import java.util.List;

/**
 * The operator's own addresses that CrowdSec is now blocking, and how that reads as an email to admins.
 *
 * <p><b>This is not a breach attempt, and saying so is the point.</b> When a block decision names a source
 * inside the fleet's {@link TrustedNetworks} — the VPN subnet, the Docker bridge, a relay's LAN, an address
 * the operator trusted by hand — nobody is attacking. The allowlist that exists to make this impossible has
 * stopped protecting the operator's own traffic, and the next thing to go is their access to the very
 * console they would fix it from. #329 names that lockout as its first risk, which is why this is the one
 * threat-detection mail that is genuinely predictive: it arrives while the operator can still act.
 *
 * <p>It therefore gets its own subject and its own body rather than a line inside something titled "Breach
 * attempt" — a rollup that told the operator they were under attack, when in fact their own allowlist had
 * failed, would send them looking in exactly the wrong direction.
 *
 * <p>The scenario that caught the address is irrelevant here; unlike {@link BreachAttemptRollup} this makes
 * no {@link ThreatKind} judgement at all. Blind scanning from inside the VPN subnet still means the
 * allowlist has failed.
 *
 * @param decisions the newly-appeared block decisions that fall inside the operator's own networks
 */
public record LockoutWarning(List<BlockDecision> decisions) {

    /**
     * The lockouts among one sweep's newly-appeared decisions.
     *
     * @param newlyAppeared   the decisions this sweep saw for the first time
     * @param trustedNetworks the operator's own networks; may be null, in which case nothing is raised —
     *                        an alarm on information Vaier could not assemble would be a guess.
     */
    public static LockoutWarning from(List<BlockDecision> newlyAppeared, TrustedNetworks trustedNetworks) {
        if (newlyAppeared == null) {
            return new LockoutWarning(List.of());
        }
        return new LockoutWarning(newlyAppeared.stream()
            .filter(decision -> decision.locksOut(trustedNetworks))
            .toList());
    }

    /** Whether anything of the operator's own is blocked. On a healthy fleet this is always false. */
    public boolean worthSending() {
        return decisions != null && !decisions.isEmpty();
    }

    /** Subject line — whose address it is comes first, so the mail cannot be mistaken for an attack. */
    public String subject() {
        if (decisions.size() == 1) {
            return "[Vaier] Lockout warning: your own " + decisions.get(0).sourceIp()
                + " is blocked at the edge";
        }
        return "[Vaier] Lockout warning: " + decisions.size()
            + " of your own addresses are blocked at the edge";
    }

    /**
     * Body: what has actually happened, which of the operator's addresses it hit, and the two verbs that
     * end it. {@code baseDomain} builds the Vaier UI link, omitted when null or blank.
     */
    public String body(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append(decisions.size() == 1
            ? "CrowdSec is blocking an address inside your own trusted networks:\n\n"
            : "CrowdSec is blocking addresses inside your own trusted networks:\n\n");
        for (BlockDecision decision : decisions) {
            body.append("  • ").append(decision.label()).append("\n");
        }
        body.append("\nThis is not an attack. The trusted networks allowlist — the VPN subnet, the Docker bridge,\n")
            .append("every relay's LAN, and every address you have trusted — is supposed to make this impossible.\n")
            .append("Something of yours is now being turned away at the edge, and you may lose access to Vaier\n")
            .append("itself while it lasts.\n");
        body.append("\nIn the Security view you can lift the block now, or trust the address for good.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
