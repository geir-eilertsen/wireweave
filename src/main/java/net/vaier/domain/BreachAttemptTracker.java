package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which CrowdSec block decisions the operator has already been told about (#329 Slice 2). The
 * sibling of {@link ImageUpdateTracker}, not {@link DiskPressureTracker} or
 * {@link BackupFailureTracker}: it is deliberately <b>not</b> baseline-quiet. A ban already active
 * the very first time Vaier's watcher polls — including right after a restart — is exactly the
 * case the operator needs to hear about, the same reasoning {@link ImageUpdateTracker} documents
 * for an image that is already stale on the first sweep.
 *
 * <p>There is also no recovery/cleared transition, unlike the level-crossing trackers: a decision
 * expiring on its own timer is not good news the way draining disk space is — it is just a ban
 * timing out — so nothing is reported when one disappears. It is still forgotten, though, so if
 * the same id is ever reused it reads as news again rather than a stale memory.
 */
public class BreachAttemptTracker {

    /** Decision id → seen. Only presence matters; the value is never read, only the key. */
    private final Map<Long, Boolean> seen = new ConcurrentHashMap<>();

    /**
     * Record a sweep's active decisions and report the ones that are newly appeared since the last
     * sweep. Ids absent from {@code decisions} are forgotten, so a decision id CrowdSec reuses
     * later is reported again rather than silently swallowed.
     */
    public synchronized List<BlockDecision> update(List<BlockDecision> decisions) {
        List<BlockDecision> newlyAppeared = new ArrayList<>();

        for (BlockDecision decision : decisions) {
            if (seen.put(decision.id(), Boolean.TRUE) == null) {
                newlyAppeared.add(decision);
            }
        }

        seen.keySet().removeIf(id -> decisions.stream().noneMatch(d -> d.id().equals(id)));
        return newlyAppeared;
    }
}
