package net.fjordomatic.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.FlushAccessSourcesUseCase;
import net.fjordomatic.domain.AccessSource;
import net.fjordomatic.domain.port.ForPublishingEvents;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Writes the access sources to disk once a minute and pushes them to the map — a plain
 * {@code rest/} driving adapter driven by a clock, like {@code PeerStatsScheduler} and
 * {@link BreachAttemptWatcher}, injecting a use case and a driven port directly.
 *
 * <p><b>Why a flush at all.</b> Recording an allowed access happens inside the forward-auth check for every
 * request to every gated service, so it stays in memory: a file write there would sit on the critical path
 * of every page load. Batching the write here is what buys that, and the {@link PreDestroy} is what stops
 * the arrangement costing the last minute of counts every time Fjord restarts — which, with self-upgrade,
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
public class AccessSourceFlushScheduler {

    /** A minute: long enough to batch a burst of requests into one write, short enough to feel live. */
    private static final long FLUSH_INTERVAL_MS = 60000;

    /** The event the map's green dots react to, on the existing security topic. */
    static final String ACCESS_SOURCES_EVENT = "access-sources";

    private final FlushAccessSourcesUseCase flushAccessSourcesUseCase;
    private final ForPublishingEvents forPublishingEvents;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = FLUSH_INTERVAL_MS)
    public void flush() {
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

    /** The last minute of counts, on the way out. Nothing is listening on the topic by now; that is fine. */
    @PreDestroy
    public void flushBeforeShutdown() {
        flush();
    }
}
