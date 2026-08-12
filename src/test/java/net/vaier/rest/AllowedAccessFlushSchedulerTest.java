package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.application.FlushAccessSourcesUseCase;
import net.vaier.application.FlushLastServicesReachedUseCase;
import net.vaier.domain.AccessSource;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AllowedAccessFlushSchedulerTest {

    private static final Instant NOON = Instant.parse("2026-08-09T12:00:00Z");

    private static final AccessSource OSLO = AccessSource.builder()
        .city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
        .count(412).firstSeen(NOON.minusSeconds(86400)).lastSeen(NOON)
        .people(List.of("geir.eilertsen@gmail.com")).build();

    FlushAccessSourcesUseCase flushAccessSources = mock(FlushAccessSourcesUseCase.class);
    FlushLastServicesReachedUseCase flushLastServicesReached = mock(FlushLastServicesReachedUseCase.class);
    ForPublishingEvents forPublishingEvents = mock(ForPublishingEvents.class);

    private AllowedAccessFlushScheduler scheduler() {
        return new AllowedAccessFlushScheduler(flushAccessSources, flushLastServicesReached,
            forPublishingEvents, new ObjectMapper());
    }

    @Test
    void flush_writesTheAccessSourcesAndPushesThemToTheSecurityView() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.of(List.of(OSLO)));

        scheduler().flush();

        verify(flushAccessSources).flushAccessSources();
        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(AllowedAccessFlushScheduler.ACCESS_SOURCES_EVENT), contains("\"city\":\"Oslo\""));
    }

    /** The payload is the controller's own shape, so the stream and the initial load cannot disagree. */
    @Test
    void flush_pushesTheDecidedLocatableFlagRatherThanRawCoordinates() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.of(List.of(OSLO)));

        scheduler().flush();

        verify(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
            eq(AllowedAccessFlushScheduler.ACCESS_SOURCES_EVENT), contains("\"locatable\":true"));
    }

    /**
     * An idle minute writes nothing, so there is nothing to push: the map's green-dot layer must not be
     * repainted once a minute forever for a payload that has not changed.
     */
    @Test
    void flush_whenNothingHasChanged_pushesNothing() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.empty());

        scheduler().flush();

        verifyNoInteractions(forPublishingEvents);
    }

    /**
     * A scheduled method that throws loses the rest of its own cycle. A store that cannot be written is
     * worth a warning, never a stack trace out of the scheduler.
     */
    @Test
    void flush_whenTheStoreCannotBeWritten_doesNotThrowAndPushesNothing() {
        doThrow(new IllegalStateException("disk full")).when(flushAccessSources).flushAccessSources();

        scheduler().flush();

        verifyNoInteractions(forPublishingEvents);
    }

    @Test
    void flush_whenThePushFails_doesNotThrow() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.of(List.of(OSLO)));
        doThrow(new IllegalStateException("nobody is listening"))
            .when(forPublishingEvents).publish(eq(BreachAttemptWatcher.SECURITY_TOPIC),
                eq(AllowedAccessFlushScheduler.ACCESS_SOURCES_EVENT), contains("Oslo"));

        scheduler().flush();
    }

    /**
     * Recording keeps the disk off the forward-auth path, which means up to a minute of counts live only
     * in memory. A restart must not be where they go.
     */
    @Test
    void shutdown_flushesWhatHasNotBeenWrittenYet() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.of(List.of(OSLO)));

        scheduler().flushBeforeShutdown();

        verify(flushAccessSources).flushAccessSources();
        verify(flushLastServicesReached).flushLastServicesReached();
    }

    /** The other half of what the forward-auth check holds in memory, on the same minute and the same exit. */
    @Test
    void flush_alsoWritesWhatEachMachineLastReached() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.empty());

        scheduler().flush();

        verify(flushLastServicesReached).flushLastServicesReached();
    }

    /** Two independent stores: one that cannot be written must not cost the other its flush. */
    @Test
    void flush_stillWritesTheLastServicesReachedWhenTheAccessSourcesCannotBeWritten() {
        doThrow(new IllegalStateException("disk full")).when(flushAccessSources).flushAccessSources();

        scheduler().flush();

        verify(flushLastServicesReached).flushLastServicesReached();
    }

    @Test
    void flush_whenTheLastServicesReachedCannotBeWritten_doesNotThrow() {
        when(flushAccessSources.flushAccessSources()).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("disk full"))
            .when(flushLastServicesReached).flushLastServicesReached();

        scheduler().flush();
    }
}
