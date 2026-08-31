package net.vaier.domain.port;

import net.vaier.domain.ScopedImage;

import java.util.Set;

/**
 * Driven port for remembering which {@link ScopedImage}s Vaier has already told admins are
 * <b>update available</b> — the latch {@link net.vaier.domain.ImageUpdateTracker} raises its edges against.
 *
 * <p>It exists because that latch was a plain in-memory map on a bean nothing persisted. Every restart it
 * came back empty, so the first sweep after one read every genuinely out-of-date image as <em>newly</em> out
 * of date and mailed it again. With this project's build-and-deploy-on-every-change habit that is several
 * restarts a day, which is why the operator kept receiving the same two or three images: those were exactly
 * the images with real pending updates.
 *
 * <p>Only images currently known to be out of date are held, so an empty store is both the first-boot state
 * and the healthy one — Vaier owes nobody an email about an image it has never judged.
 *
 * <p>Loading is tolerant by contract: a missing or unreadable store is "know nothing", never a failure. The
 * cost of forgetting is one duplicate mail; the cost of throwing is a sweep that dies and takes every alert
 * in it with it.
 */
public interface ForPersistingImageUpdateState {

    /** Every scoped image Vaier currently knows to be update available. Empty when it knows of none. */
    Set<ScopedImage> loadOutOfDate();

    /** Replace what is remembered with {@code images} — the complete set, not an addition to it. */
    void saveOutOfDate(Set<ScopedImage> images);
}
