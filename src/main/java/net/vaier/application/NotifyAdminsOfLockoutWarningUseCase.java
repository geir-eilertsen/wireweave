package net.vaier.application;

import net.vaier.domain.LockoutWarning;

/**
 * Tell admins that CrowdSec is blocking one of their own trusted networks — the allowlist has stopped
 * protecting them and their access to Vaier itself is at risk. Deliberately separate from
 * {@link NotifyAdminsOfBreachAttemptUseCase}: this one is not an attack, and the two mails must never be
 * confused for one another.
 *
 * <p>Driven by the scheduled breach-attempt watcher, which raises it only on newly-appeared decisions, so a
 * standing lockout does not mail every sweep.
 */
public interface NotifyAdminsOfLockoutWarningUseCase {

    /** Mail every admin one warning for the operator's own addresses named in {@code warning}. */
    void notifyAdminsOfLockoutWarning(LockoutWarning warning);
}
