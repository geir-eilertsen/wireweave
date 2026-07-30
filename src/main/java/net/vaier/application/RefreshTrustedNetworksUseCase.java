package net.vaier.application;

/**
 * Re-renders the CrowdSec trusted-networks allowlist (#329 Slice 1) from the current VPN
 * subnet, Docker bridge CIDR, and every relay peer's {@code lanCidr}. Called on Vaier boot and
 * whenever a relay's {@code lanCidr} changes or a peer is deleted, so the operator's own
 * networks are never subject to a false-positive block decision.
 */
public interface RefreshTrustedNetworksUseCase {

    void refreshTrustedNetworks();
}
