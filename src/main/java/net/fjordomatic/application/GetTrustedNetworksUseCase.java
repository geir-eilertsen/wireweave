package net.fjordomatic.application;

import net.fjordomatic.domain.TrustedNetworks;

/**
 * Read the fleet's trusted networks — the VPN subnet, the Docker bridge CIDR, every relay peer's
 * {@code lanCidr}, and every address the operator has trusted by hand.
 *
 * <p>The same allowlist {@link RefreshTrustedNetworksUseCase} renders into CrowdSec's whitelist file, read
 * rather than written. The breach-attempt sweep needs it to tell a ban on a stranger apart from a ban that
 * is locking the operator out of their own fleet; assembling it a second time in the watcher would let the
 * two definitions drift.
 */
public interface GetTrustedNetworksUseCase {

    TrustedNetworks getTrustedNetworks();
}
