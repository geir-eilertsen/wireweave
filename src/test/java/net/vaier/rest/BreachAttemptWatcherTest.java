package net.vaier.rest;

import net.vaier.application.NotifyAdminsOfBreachAttemptUseCase;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.port.ForDetectingIntrusions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BreachAttemptWatcherTest {

    private static final BlockDecision DECISION =
        new BlockDecision(1L, "crowdsecurity/http-probing", "1.2.3.4", "ban", "3h59m48s");

    ForDetectingIntrusions forDetectingIntrusions;
    NotifyAdminsOfBreachAttemptUseCase notifier;
    BreachAttemptWatcher watcher;

    @BeforeEach
    void setUp() {
        forDetectingIntrusions = mock(ForDetectingIntrusions.class);
        notifier = mock(NotifyAdminsOfBreachAttemptUseCase.class);
        watcher = new BreachAttemptWatcher(forDetectingIntrusions, notifier);
    }

    @Test
    void aNewDecisionOnTheFirstSweepNotifiesOnce() {
        when(forDetectingIntrusions.getActiveDecisions()).thenReturn(List.of(DECISION));

        watcher.checkForBreachAttempts();

        ArgumentCaptor<net.vaier.domain.BreachAttemptRollup> rollup =
            ArgumentCaptor.forClass(net.vaier.domain.BreachAttemptRollup.class);
        verify(notifier).notifyAdminsOfBreachAttempt(rollup.capture());
        assertThat(rollup.getValue().decisions()).containsExactly(DECISION);
    }

    @Test
    void theSameDecisionOnASecondSweepDoesNotReNotify() {
        when(forDetectingIntrusions.getActiveDecisions()).thenReturn(List.of(DECISION));
        watcher.checkForBreachAttempts();

        watcher.checkForBreachAttempts();

        // Exactly the one notification from the first sweep — the second sweep found nothing new.
        verify(notifier, org.mockito.Mockito.times(1)).notifyAdminsOfBreachAttempt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void noActiveDecisionsNeverNotifies() {
        when(forDetectingIntrusions.getActiveDecisions()).thenReturn(List.of());

        watcher.checkForBreachAttempts();

        verifyNoInteractions(notifier);
    }

    @Test
    void aFailedNotificationSendDoesNotPropagate() {
        when(forDetectingIntrusions.getActiveDecisions()).thenReturn(List.of(DECISION));
        doThrow(new RuntimeException("smtp down")).when(notifier).notifyAdminsOfBreachAttempt(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkForBreachAttempts())
            .doesNotThrowAnyException();
    }
}
