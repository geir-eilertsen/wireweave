package net.vaier.domain.port;

import net.vaier.domain.LastServiceReached;
import net.vaier.domain.LastServicesReached;

/**
 * The store of what each machine last reached through the forward-auth check.
 *
 * <p><b>Write-behind, and deliberately so.</b> {@link #save} is called from inside the check that
 * authenticates every request to every gated service, so it may not touch the disk; {@link #flush} is what
 * the disk is for, driven once a minute by a scheduler and once more on the way out. What
 * {@link #getAll} answers is what has been saved, not what was last written — the peer view must show the
 * service a phone reached a second ago, not the one it had reached at the last flush.
 *
 * <p>The write is <b>intent-shaped</b>: one machine's reach, never a whole collection read a moment
 * earlier. Merging inside the store's own lock is what keeps two concurrent accesses from erasing each
 * other, and how they merge stays the domain's rule ({@link LastServicesReached#with}).
 */
public interface ForPersistingLastServicesReached {

    LastServicesReached getAll();

    /** Records that machine's reach, superseding whatever it had reached before. Never writes to disk. */
    void save(LastServiceReached reached);

    /** Writes what has been recorded since the last flush, if anything has. */
    void flush();
}
