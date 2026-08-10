package net.fjordomatic.application;

/**
 * Re-renders the CrowdSec trusted-networks allowlist (#329 Slice 1) from the current VPN
 * subnet, Docker bridge CIDR, and every relay peer's {@code lanCidr}. Called on Fjord boot
 * ({@code SecurityService}) and on a recurring schedule ({@code TrustedNetworksScheduler}),
 * deliberately decoupled from {@code VpnService} — a lanCidr change taking a few minutes to
 * reach the allowlist costs nothing real, since CrowdSec doesn't hot-reload the file either way.
 */
public interface RefreshTrustedNetworksUseCase {

    void refreshTrustedNetworks();
}
