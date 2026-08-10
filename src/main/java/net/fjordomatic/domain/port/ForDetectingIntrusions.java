package net.fjordomatic.domain.port;

import net.fjordomatic.domain.BlockDecision;
import net.fjordomatic.domain.BlockDecisionsUnreadableException;

import java.util.List;

/**
 * Reads CrowdSec's currently active block decisions (#329 Slice 2).
 *
 * <p>The same question has two honest answers depending on who is asking, so this port offers both and
 * neither is named as the innocent default. <b>Do not "fix" the inconsistency by collapsing them</b> — it
 * is the whole point, and it was written after the collapsed version shipped a live defect: the security
 * view told an operator "nobody is blocked right now" while {@code cscli} listed eleven active decisions,
 * because the first exec after a container restart failed cold and the silent read reported it as nothing.
 *
 * <ul>
 *   <li>{@link #getActiveDecisionsOrEmpty()} — the silent read. Any failure reads as no active decisions.
 *   For {@link net.fjordomatic.domain.BreachAttemptTracker}, which diffs one sweep against the last: an outage
 *   that threw would cost the sweep, and an outage that reported bans would mail the operator a breach that
 *   never happened. Reporting nothing is the only answer that neither lies nor alarms — the tracker simply
 *   sees no <em>new</em> bans and waits five minutes.</li>
 *   <li>{@link #getActiveDecisionsOrFail()} — the loud read. A failure throws
 *   {@link BlockDecisionsUnreadableException}. For anything an operator is looking at, where an empty list
 *   is read as an all-clear. "I could not ask" and "nobody is attacking" are opposite facts about the
 *   fleet's safety and must never share a rendering.</li>
 * </ul>
 *
 * <p>How a CrowdSec failure actually looks — a dead exec, an error line where JSON was expected, the
 * literal word {@code null} a quiet stack prints — is the adapter's knowledge alone. Both methods above
 * describe only what the caller may conclude.
 */
public interface ForDetectingIntrusions {

    /**
     * The active decisions, or an empty list if they could not be read. Never throws. The emptiness is
     * therefore <em>not</em> evidence that nothing is blocked — only a caller that can live with that,
     * like the breach-attempt sweep, may use this.
     */
    List<BlockDecision> getActiveDecisionsOrEmpty();

    /**
     * The active decisions, or {@link BlockDecisionsUnreadableException} if they could not be read. An
     * empty list from this method is an answer: CrowdSec was asked and is keeping nobody out.
     */
    List<BlockDecision> getActiveDecisionsOrFail();
}
