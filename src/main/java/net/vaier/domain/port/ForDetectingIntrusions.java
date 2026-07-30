package net.vaier.domain.port;

import net.vaier.domain.BlockDecision;

import java.util.List;

/**
 * Reads CrowdSec's currently active block decisions (#329 Slice 2). A transient failure — LAPI
 * unreachable, a bad or missing key, a malformed response — reads as no active decisions, never a
 * thrown exception: {@link net.vaier.domain.BreachAttemptTracker} must not mistake an outage for
 * every ban clearing at once.
 */
public interface ForDetectingIntrusions {

    List<BlockDecision> getActiveDecisions();
}
