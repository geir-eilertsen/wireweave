package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetTrustedNetworksUseCase;
import net.vaier.application.NotifyAdminsOfBreachAttemptUseCase;
import net.vaier.application.NotifyAdminsOfLockoutWarningUseCase;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BreachAttemptRollup;
import net.vaier.domain.BreachAttemptTracker;
import net.vaier.domain.LockoutWarning;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls CrowdSec's active block decisions, feeds the security view, and raises the two alarms a block
 * decision can be worth (#329). The sibling of {@code RemoteDiskWatcher}/{@code BackupServerWatcher}: a
 * plain {@code rest/} scheduler injecting a driven port and use cases directly, no intermediate service.
 *
 * <p><b>Almost nothing here mails.</b> Slice 2 mailed every newly-appeared decision and the operator's
 * verdict was "i get too many breach mails and i cannot act on them so useless" — twenty-four the first
 * day. A banned scanner is CrowdSec working correctly, which is the opposite of what a notification is
 * for. What survives is a {@link BreachAttemptRollup} for a genuine credential attack, and a
 * {@link LockoutWarning} when CrowdSec starts blocking the operator's own networks. Which decisions
 * qualify is the domain's call, not this watcher's — see both types' {@code from} factories.
 *
 * <p>It is also the security view's feed (#329 Slice 3b), and that is what makes the silence affordable:
 * every decision is pushed every sweep regardless of whether anything is mailed. The sweep result is
 * already in its hands each cycle, so pushing it costs one serialisation and saves a second reader of
 * CrowdSec — and the browser never polls, which is the standing rule: the backend polls, SSE pushes. The
 * payload is {@link SecurityRestController.BlockDecisionResponse}, deliberately the exact shape the REST
 * read returns, so the stream and the initial load can never disagree.
 */
@Component
@Slf4j
public class BreachAttemptWatcher {

    /** Matches {@code RemoteDiskWatcher}/{@code BackupServerWatcher}'s existing 5-minute cadence. */
    private static final long SWEEP_INTERVAL_MS = 300000;

    /** The SSE topic the security view reacts to; the event it carries. */
    static final String SECURITY_TOPIC = "security";
    static final String DECISIONS_EVENT = "block-decisions";

    private final ForDetectingIntrusions forDetectingIntrusions;
    private final NotifyAdminsOfBreachAttemptUseCase notifier;
    private final NotifyAdminsOfLockoutWarningUseCase lockoutNotifier;
    private final GetTrustedNetworksUseCase getTrustedNetworksUseCase;
    private final ForPublishingEvents forPublishingEvents;
    private final ObjectMapper objectMapper;
    private final BreachAttemptTracker tracker = new BreachAttemptTracker();

    public BreachAttemptWatcher(ForDetectingIntrusions forDetectingIntrusions,
                                NotifyAdminsOfBreachAttemptUseCase notifier,
                                NotifyAdminsOfLockoutWarningUseCase lockoutNotifier,
                                GetTrustedNetworksUseCase getTrustedNetworksUseCase,
                                ForPublishingEvents forPublishingEvents,
                                ObjectMapper objectMapper) {
        this.forDetectingIntrusions = forDetectingIntrusions;
        this.notifier = notifier;
        this.lockoutNotifier = lockoutNotifier;
        this.getTrustedNetworksUseCase = getTrustedNetworksUseCase;
        this.forPublishingEvents = forPublishingEvents;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS)
    public void checkForBreachAttempts() {
        List<BlockDecision> active = forDetectingIntrusions.getActiveDecisionsOrEmpty();
        publishSweep(active);

        TrustedNetworks trustedNetworks;
        try {
            trustedNetworks = getTrustedNetworksUseCase.getTrustedNetworks();
        } catch (Exception e) {
            // Read before the tracker is told anything, and bail before it is: without the allowlist there
            // is no telling a stranger's ban from the operator's own, and marking these decisions as
            // told-about would lose them for good. Deferred to the next sweep, not swallowed.
            log.warn("Skipping this breach sweep — the trusted networks could not be read: {}", e.getMessage());
            return;
        }

        List<BlockDecision> newlyAppeared = tracker.update(active);

        // The lockout warning goes first: it is the one that says the operator is about to lose the console
        // they would fix all of this from.
        raise(LockoutWarning.from(newlyAppeared, trustedNetworks));
        raise(BreachAttemptRollup.from(newlyAppeared, trustedNetworks));
    }

    /** Each send is guarded on its own, so a failing SMTP call for one alarm cannot cost the other. */
    private void raise(LockoutWarning warning) {
        if (!warning.worthSending()) {
            return;
        }
        try {
            lockoutNotifier.notifyAdminsOfLockoutWarning(warning);
        } catch (Exception e) {
            log.warn("Failed to send lockout warning: {}", e.getMessage());
        }
    }

    private void raise(BreachAttemptRollup rollup) {
        if (!rollup.worthSending()) {
            return;
        }
        try {
            notifier.notifyAdminsOfBreachAttempt(rollup);
        } catch (Exception e) {
            log.warn("Failed to send breach-attempt notification: {}", e.getMessage());
        }
    }

    /**
     * Push this sweep to the security view — every sweep, including the one that finds nothing, since an
     * empty push is how a lifted or expired block leaves the view. Every decision goes, whether or not it
     * was worth mailing: the view is where the routine blocks live now.
     *
     * <p>Inside its own try/catch, like {@code PeerStatsScheduler}: a scheduled method that throws loses
     * the rest of its own cycle, and a topic nobody is listening on must never cost the operator the email
     * this watcher exists to send.
     */
    private void publishSweep(List<BlockDecision> active) {
        try {
            forPublishingEvents.publish(SECURITY_TOPIC, DECISIONS_EVENT,
                objectMapper.writeValueAsString(SecurityRestController.responses(active)));
        } catch (Exception e) {
            log.debug("Failed to publish the block-decision sweep via SSE: {}", e.getMessage());
        }
    }
}
