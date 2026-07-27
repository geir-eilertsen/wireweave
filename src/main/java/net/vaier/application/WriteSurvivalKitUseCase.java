package net.vaier.application;

import net.vaier.domain.SurvivalKitHosts;
import net.vaier.domain.SurvivalKitRollout;

/**
 * Write this fleet's survival kit and put it where it will still be found.
 *
 * <p>One call does the whole thing — choose the hosts, render the kit, encrypt it, write it everywhere —
 * because the operator's decision is not any of those steps. It is the single decision the feature exists
 * for: <em>make sure I can still read my backups if this server is gone</em>.
 */
public interface WriteSurvivalKitUseCase {

    /**
     * @throws IllegalStateException when no kit passphrase has been chosen yet. There is no default and no
     *                               generated one: a kit Vaier could open by itself would die with Vaier,
     *                               and an unprotected kit hands the fleet to whoever picks up a copy.
     */
    SurvivalKitReport writeSurvivalKit();

    /**
     * What one write achieved: where Vaier decided the copies should go (with its reasoning, including the
     * machines it passed over), and what actually happened when it tried.
     */
    record SurvivalKitReport(SurvivalKitHosts.Selection selection, SurvivalKitRollout.Result rollout) {}
}
