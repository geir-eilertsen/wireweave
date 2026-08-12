package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.FlushAccessSourcesUseCase;
import net.vaier.application.FlushLastServicesReachedUseCase;
import net.vaier.domain.AccessSource;
import net.vaier.domain.port.ForPublishingEvents;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Writes what an <b>allowed access</b> leaves behind to disk once a minute — the <b>access sources</b>,
 * which it also pushes to the map, and the <b>last service reached</b>. A plain {@code rest/} driving
 * adapter driven by a clock, like {@code PeerStatsScheduler} and {@link BreachAttemptWatcher}, injecting
 * use cases and a driven port directly.
 *
 * <p>Named for what both records are derived from rather than for either of them: they are recorded on the
 * same forward-auth path, held in memory for the same reason, and flushed on the same minute and the same
 * shutdown. One clock, two stores — a second scheduler would be a second thing to get the shutdown flush
 * right in, and a third allowed-access-derived record would belong here too.
 *
 * <p><b>Why a flush at all.</b> Recording an allowed access happens inside the forward-auth check for every
 * request to every gated service, so it stays in memory: a file write there would sit on the critical path
 * of every page load. Batching the write here is what buys that, and the {@link PreDestroy} is what stops
 * the arrangement costing the last minute of counts every time Vaier restarts — which, with self-upgrade,
 * is often enough to notice.
 *
 * <p>It doubles as the map's feed for the green dots, exactly as the breach sweep does for the threat
 * pings: the browser never polls, the backend does. The map is the only surface that draws these — the
 * security view has no access-sources section — so the push repaints that one layer in place. The payload
 * is {@code SecurityRestController.AccessSourceResponse}, deliberately the same shape the REST read
 * returns, so the stream and the initial load can never disagree.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AllowedAccessFlushScheduler {

    /** A minute: long enough to batch a burst of requests into one write, short enough to feel live. */
    private static final long FLUSH_INTERVAL_MS = 60000;

    /** The event the map's green dots react to, on the existing security topic. */
    static final String ACCESS_SOURCES_EVENT = "access-sources";

    private final FlushAccessSourcesUseCase flushAccessSourcesUseCase;
    private final FlushLastServicesReachedUseCase flushLastServicesReachedUseCase;
    private final ForPublishingEvents forPublishingEvents;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flush() {
        flushLastServicesReached();
        Optional<List<AccessSource>> flushed;
        try {
            flushed = flushAccessSourcesUseCase.flushAccessSources();
        } catch (Exception e) {
            // Loud, but not fatal: the counts are still in memory and the next flush will try again. A
            // scheduled method that throws loses the rest of its own cycle.
            log.warn("Could not write the access sources: {}", e.getMessage());
            return;
        }
        // Nothing was written, so there is nothing for the browser to repaint either.
        if (flushed.isEmpty()) return;
        try {
            forPublishingEvents.publish(BreachAttemptWatcher.SECURITY_TOPIC, ACCESS_SOURCES_EVENT,
                objectMapper.writeValueAsString(
                    SecurityRestController.accessSourceResponses(flushed.get())));
        } catch (Exception e) {
            log.debug("Failed to publish the access sources via SSE: {}", e.getMessage());
        }
    }

    /**
     * The other half of what the forward-auth check holds in memory. First, and in its own guard: two
     * independent stores, and an access-sources write that fails must not cost this one its flush.
     *
     * <p>Nothing is pushed for it — the peer view reads the store, which answers what has been recorded
     * rather than what was last written, so there is no stale payload for a push to correct.
     */
    private void flushLastServicesReached() {
        try {
            flushLastServicesReachedUseCase.flushLastServicesReached();
        } catch (Exception e) {
            log.warn("Could not write what each machine last reached: {}", e.getMessage());
        }
    }

    /** The last minute of counts, on the way out. Nothing is listening on the topic by now; that is fine. */
    @PreDestroy
    public void flushBeforeShutdown() {
        flush();
    }
}
