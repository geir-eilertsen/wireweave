package net.fjordomatic.application;

import net.fjordomatic.domain.AccessSource;

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
 */
public interface FlushAccessSourcesUseCase {

    Optional<List<AccessSource>> flushAccessSources();
}
