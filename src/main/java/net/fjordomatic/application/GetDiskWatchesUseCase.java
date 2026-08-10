package net.fjordomatic.application;

import net.fjordomatic.domain.DiskWatches;

/**
 * Read the fleet's disk watches — which filesystem on which machine Fjord alerts on, and at what threshold
 * (#325). What the scheduled disk watcher reads before it judges a reading.
 *
 * <p>The answer is never "no watch": {@link DiskWatches#forFilesystem} resolves an unconfigured filesystem
 * to watched, at the global threshold. Nothing is ever silently unwatched.
 */
public interface GetDiskWatchesUseCase {

    /** Every stored disk watch, resolvable by machine and mount point. */
    DiskWatches getDiskWatches();
}
