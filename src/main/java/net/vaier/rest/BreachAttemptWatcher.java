package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.NotifyAdminsOfBreachAttemptUseCase;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BreachAttemptRollup;
import net.vaier.domain.BreachAttemptTracker;
import net.vaier.domain.port.ForDetectingIntrusions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls CrowdSec's active block decisions and emails admins one rollup when new ones appear
 * (#329 Slice 2) — "notify the operator when someone tries to break in". The sibling of
 * {@code RemoteDiskWatcher}/{@code BackupServerWatcher}: a plain {@code rest/} scheduler injecting
 * a driven port and a use case directly, no intermediate service.
 */
@Component
@Slf4j
public class BreachAttemptWatcher {

    /** Matches {@code RemoteDiskWatcher}/{@code BackupServerWatcher}'s existing 5-minute cadence. */
    private static final long SWEEP_INTERVAL_MS = 300000;

    private final ForDetectingIntrusions forDetectingIntrusions;
    private final NotifyAdminsOfBreachAttemptUseCase notifier;
    private final BreachAttemptTracker tracker = new BreachAttemptTracker();

    public BreachAttemptWatcher(ForDetectingIntrusions forDetectingIntrusions,
                                NotifyAdminsOfBreachAttemptUseCase notifier) {
        this.forDetectingIntrusions = forDetectingIntrusions;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void checkForBreachAttempts() {
        List<BlockDecision> active = forDetectingIntrusions.getActiveDecisions();
        List<BlockDecision> newlyAppeared = tracker.update(active);

        BreachAttemptRollup rollup = new BreachAttemptRollup(newlyAppeared);
        if (!rollup.worthSending()) {
            return;
        }
        try {
            notifier.notifyAdminsOfBreachAttempt(rollup);
        } catch (Exception e) {
            log.warn("Failed to send breach-attempt notification: {}", e.getMessage());
        }
    }
}
