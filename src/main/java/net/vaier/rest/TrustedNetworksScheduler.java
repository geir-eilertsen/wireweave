package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.RefreshTrustedNetworksUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the CrowdSec trusted-networks allowlist (#329) in sync with the fleet's own VPN topology,
 * on its own schedule — decoupled from {@code VpnService} entirely. A lanCidr change taking a few
 * minutes to reach the allowlist file costs nothing real: CrowdSec doesn't hot-reload a changed
 * parser file either way (see PRD §6.26), so there was never a correctness reason for
 * {@code VpnService} to trigger this synchronously, only an architectural shortcut that stretched
 * the one documented services-call-a-use-case exception (the peer-delete → published-service
 * cascade) to a case that doesn't share the property that justifies it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrustedNetworksScheduler {

    private final RefreshTrustedNetworksUseCase refreshTrustedNetworksUseCase;

    @Scheduled(fixedDelay = 300000)
    public void refresh() {
        try {
            refreshTrustedNetworksUseCase.refreshTrustedNetworks();
        } catch (RuntimeException e) {
            log.warn("Failed to refresh the CrowdSec trusted-networks allowlist", e);
        }
    }
}
