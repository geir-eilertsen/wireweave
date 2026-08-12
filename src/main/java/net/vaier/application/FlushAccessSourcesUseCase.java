package net.vaier.application;

import net.vaier.domain.AccessSource;

import java.util.List;
import java.util.Optional;

/**
 * Write what has been recorded since the last flush to disk, forgetting the places nobody has come from in
 * a month on the way. Driven by a clock, and by shutdown — recording keeps the disk off the forward-auth
 * path, so this is where the counts are made to survive a restart.
 *
 * <p>Returns what it saved, so the caller can push exactly that to the browser rather than reading the
 * collection a second time and pushing something subtly different. Empty means there was nothing to write —
 * nothing has changed since the last successful save — and the caller has nothing to push either. An idle
 * fleet would otherwise rewrite the same file, and repaint the same map, every minute forever.
 *
 * <p>The clock that drives this is also the only clock Vaier has off the forward-auth path, so the
 * implementation re-reads the server's own public address here too — the fact a <b>hairpinned access</b> is
 * recognised by. Resolving it costs an HTTP call, and every other way into the access sources is a request
 * to a gated service.
 */
public interface FlushAccessSourcesUseCase {

    Optional<List<AccessSource>> flushAccessSources();
}
