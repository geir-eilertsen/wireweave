package net.fjordomatic.domain;

/**
 * Fjord asked CrowdSec who it is currently keeping out and could not find out (#329 Slice 3).
 *
 * <p>The sibling of {@link BlockNotLiftedException}, and for the same reason: a security screen that cannot
 * reach its source must say so. An empty list is the sentence "nobody is blocked right now" on the
 * operator's screen — the single most reassuring thing Fjord can say — and saying it because the read failed
 * is worse than saying nothing at all.
 *
 * <p>Deliberately <em>not</em> thrown on
 * {@link net.fjordomatic.domain.port.ForDetectingIntrusions#getActiveDecisionsOrEmpty()}, whose silence the
 * breach-attempt sweep depends on: a throw would cost that sweep, and reading an outage as "every ban
 * cleared" would mail the operator a breach that never happened. Two callers, two right answers.
 *
 * <p>Surfaces as {@code 502} — the failure is on the far side of Fjord, in the engine that owns the bans —
 * exactly like {@link BlockNotLiftedException} and {@link ArchivesUnreadableException}.
 */
public class BlockDecisionsUnreadableException extends RuntimeException {

    public BlockDecisionsUnreadableException(String message) {
        super(message);
    }

    public BlockDecisionsUnreadableException(String message, Throwable cause) {
        super(message, cause);
    }
}
