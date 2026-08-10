package net.fjordomatic.domain.port;

import net.fjordomatic.domain.AccessSources;

/**
 * Where the places allowed accesses came from are kept between restarts. Whole-collection reads and
 * writes: pruning removes places, so a per-entry save could never make one leave.
 */
public interface ForPersistingAccessSources {

    AccessSources getAll();

    void save(AccessSources sources);
}
