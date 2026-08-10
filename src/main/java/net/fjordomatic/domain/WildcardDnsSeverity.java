package net.fjordomatic.domain;

/**
 * How much of the operator's attention a {@link WildcardDnsStatus} deserves. Kept separate from the
 * status itself because "what was found" and "how bad is that" are different questions, and only the
 * second one is what a log level or a note's styling is really asking.
 */
public enum WildcardDnsSeverity {
    /** Nothing to do. */
    OK,
    /** Worth saying, but not evidence anything is broken. */
    WARNING,
    /** Something the operator has to fix before published services will work. */
    ERROR
}
