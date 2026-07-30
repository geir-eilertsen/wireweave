package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.RefreshTrustedNetworksUseCase;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The fleet-threat-detection domain concept (#329). Slice 1 scopes it to one job: keep the
 * CrowdSec trusted-networks allowlist in sync. The bouncer's own API key is a compose-level
 * shared secret ({@code VAIER_CROWDSEC_BOUNCER_KEY}, install.sh-generated exactly like
 * {@code VAIER_DEX_CLIENT_SECRET}) — CrowdSec's own image self-registers the bouncer from its
 * {@code BOUNCER_KEY_vaier} env var on every boot, so no exec/mint step belongs here. This
 * service grows to cover ban/unban and threat-signal notifications in later slices — same
 * service, more use cases, per the project's one-service-per-domain rule.
 */
@Service
@Slf4j
public class SecurityService implements RefreshTrustedNetworksUseCase {

    @Value("${wireguard.vpn.subnet:10.13.13.0/24}")
    private String vpnSubnet;

    /**
     * The {@code vaier-network} Docker bridge CIDR. Kept as its own literal-backed value rather
     * than reusing {@code LaunchpadRestController.trusted-proxy-cidr} — same physical value today,
     * but a different concept (trusted reverse-proxy header source vs. threat-detection allowlist),
     * so the two configs are not force-coupled through an unrelated interface.
     */
    @Value("${security.docker-bridge-cidr:172.20.0.0/16}")
    private String dockerBridgeCidr;

    private final ForGettingPeerConfigurations peerConfigProvider;
    private final ForWritingCrowdSecWhitelist forWritingCrowdSecWhitelist;

    public SecurityService(ForGettingPeerConfigurations peerConfigProvider,
                           ForWritingCrowdSecWhitelist forWritingCrowdSecWhitelist) {
        this.peerConfigProvider = peerConfigProvider;
        this.forWritingCrowdSecWhitelist = forWritingCrowdSecWhitelist;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        refreshTrustedNetworks();
    }

    // --- RefreshTrustedNetworksUseCase ---

    @Override
    public void refreshTrustedNetworks() {
        List<String> relayLanCidrs =
            ForGettingPeerConfigurations.allLanCidrs(peerConfigProvider.getAllPeerConfigs());
        TrustedNetworks trustedNetworks = TrustedNetworks.of(vpnSubnet, dockerBridgeCidr, relayLanCidrs);
        forWritingCrowdSecWhitelist.write(trustedNetworks);
    }
}
