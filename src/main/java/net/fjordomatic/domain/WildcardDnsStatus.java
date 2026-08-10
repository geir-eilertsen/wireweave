package net.fjordomatic.domain;

import lombok.Getter;

/**
 * What Fjord found when it checked the operator's single {@code *.<domain>} record at boot.
 *
 * <p>Each status carries its own {@link #getSeverity()} and {@link #getLabel()}. Both are judgments about
 * the operator's situation, not rendering details, so they live here — a caller (a log line, a
 * settings pane) asks the domain rather than re-deriving them. They were briefly re-derived in the
 * browser and had already drifted out of step with the boot log by the time that was noticed.
 */
@Getter
public enum WildcardDnsStatus {
    /** A random name under the domain resolves, and it resolves to this server. Nothing to do. */
    COVERED(WildcardDnsSeverity.OK, "Covered"),
    /** Nothing answers for names under the domain — the wildcard record has not been created. */
    NOT_RESOLVING(WildcardDnsSeverity.ERROR, "Not resolving"),
    /** Names under the domain resolve, but to some other machine. */
    RESOLVES_ELSEWHERE(WildcardDnsSeverity.ERROR, "Resolves elsewhere"),
    /** Names resolve, but this server's own public address is unknown, so the answer cannot be judged. */
    UNCONFIRMED(WildcardDnsSeverity.WARNING, "Unconfirmed");

    /** How much of the operator's attention this verdict deserves. */
    private final WildcardDnsSeverity severity;

    /** The verdict in the operator's words — never the enum's name, which is Fjord's. */
    private final String label;

    WildcardDnsStatus(WildcardDnsSeverity severity, String label) {
        this.severity = severity;
        this.label = label;
    }

    /** Whether this verdict is something the operator has to do something about. */
    public boolean needsOperatorAction() {
        return severity != WildcardDnsSeverity.OK;
    }
}
