package net.vaier.application;

import net.vaier.domain.BreachAttemptRollup;

/**
 * Tell admins a new CrowdSec block decision appeared (#329 Slice 2) — "notify the operator when
 * someone tries to break in". Driven by the scheduled breach-attempt watcher, which raises it only
 * on newly-appeared decisions, never on one already reported.
 */
public interface NotifyAdminsOfBreachAttemptUseCase {

    /** Mail every admin one rollup for the decisions in {@code rollup}. One sweep is one mail, never one per decision. */
    void notifyAdminsOfBreachAttempt(BreachAttemptRollup rollup);
}
