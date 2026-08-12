package net.vaier.application;

/**
 * Write what each machine has reached since the last flush to disk.
 *
 * <p>The counterpart of {@link FlushAccessSourcesUseCase}, and it exists for the same reason: recording
 * rides on the check that authenticates every request to every gated service, so the disk is touched here,
 * on a clock, and never there. Nothing is handed back — the peer view reads the store directly, so there is
 * no payload to push and nothing a caller could do with one.
 */
public interface FlushLastServicesReachedUseCase {

    void flushLastServicesReached();
}
