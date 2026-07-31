package net.vaier.domain.port;

import net.vaier.domain.SourceAddress;

import java.util.List;

/**
 * The store of addresses the operator has permanently trusted (#329 Slice 3c).
 *
 * <p>It exists because the CrowdSec whitelist file is <em>derived</em>, not authoritative:
 * {@code CrowdSecWhitelistFileAdapter} rewrites it wholesale from {@link net.vaier.domain.TrustedNetworks}
 * every five minutes, so an address appended to that file out of band is erased within five minutes. The
 * operator's decision has to live somewhere the rewrite reads <em>from</em> — here — and be folded back
 * into {@code TrustedNetworks} on every refresh.
 *
 * <p>Like every other file-backed store under {@code VAIER_CONFIG_PATH}, a missing file is the healthy
 * first-boot state and reads as an empty list, never an error.
 */
public interface ForPersistingTrustedAddresses {

    List<SourceAddress> getAll();

    /** Stores {@code address}; storing one that is already trusted is a no-op, never a duplicate row. */
    void save(SourceAddress address);
}
