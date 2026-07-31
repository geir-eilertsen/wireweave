package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetTrustedAddressesUseCase;
import net.vaier.application.GetTrustedNetworksUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.RefreshTrustedNetworksUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UntrustAddressUseCase;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForLiftingBlocks;
import net.vaier.domain.port.ForPersistingTrustedAddresses;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The fleet-threat-detection domain concept (#329). Slice 1 scoped it to one job: keep the
 * CrowdSec trusted-networks allowlist in sync. The bouncer's own API key is a compose-level
 * shared secret ({@code VAIER_CROWDSEC_BOUNCER_KEY}, install.sh-generated exactly like
 * {@code VAIER_DEX_CLIENT_SECRET}) — CrowdSec's own image self-registers the bouncer from its
 * {@code BOUNCER_KEY_vaier} env var on every boot, so no exec/mint step belongs here.
 *
 * <p>Slice 3 adds the operator's side of it: read who is currently blocked, let one address back in, and
 * trust one for good. Same service, more use cases, per the project's one-service-per-domain rule. Every
 * decision it needs — is this string an address at all, what CIDR does a bare address become — belongs to
 * {@link SourceAddress}; this class only passes ports in and orchestrates.
 */
@Service
@Slf4j
public class SecurityService implements RefreshTrustedNetworksUseCase, GetTrustedNetworksUseCase,
    GetBlockDecisionsUseCase, LiftBlockUseCase, TrustAddressUseCase, GetTrustedAddressesUseCase,
    UntrustAddressUseCase {

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
    private final ForDetectingIntrusions forDetectingIntrusions;
    private final ForLiftingBlocks forLiftingBlocks;
    private final ForPersistingTrustedAddresses forPersistingTrustedAddresses;

    public SecurityService(ForGettingPeerConfigurations peerConfigProvider,
                           ForWritingCrowdSecWhitelist forWritingCrowdSecWhitelist,
                           ForDetectingIntrusions forDetectingIntrusions,
                           ForLiftingBlocks forLiftingBlocks,
                           ForPersistingTrustedAddresses forPersistingTrustedAddresses) {
        this.peerConfigProvider = peerConfigProvider;
        this.forWritingCrowdSecWhitelist = forWritingCrowdSecWhitelist;
        this.forDetectingIntrusions = forDetectingIntrusions;
        this.forLiftingBlocks = forLiftingBlocks;
        this.forPersistingTrustedAddresses = forPersistingTrustedAddresses;
    }

    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        refreshTrustedNetworks();
    }

    // --- RefreshTrustedNetworksUseCase ---

    @Override
    public void refreshTrustedNetworks() {
        forWritingCrowdSecWhitelist.write(getTrustedNetworks());
    }

    // --- GetTrustedNetworksUseCase ---

    /**
     * Assembled fresh on every call, from the same three sources the whitelist file is rendered from —
     * there is deliberately no cached copy, because a cached allowlist is one that can disagree with the
     * file CrowdSec is actually enforcing.
     *
     * <p>The permanently trusted addresses have to be read every time: {@link #refreshTrustedNetworks()}
     * rewrites the whitelist file wholesale, so an address left out here is erased from it within five
     * minutes.
     */
    @Override
    public TrustedNetworks getTrustedNetworks() {
        List<String> relayLanCidrs =
            ForGettingPeerConfigurations.allLanCidrs(peerConfigProvider.getAllPeerConfigs());
        return TrustedNetworks.of(vpnSubnet, dockerBridgeCidr, relayLanCidrs,
            forPersistingTrustedAddresses.getAll());
    }

    // --- GetBlockDecisionsUseCase ---

    /**
     * The loud read, never the sweep's silent one: this use case feeds the operator's security screen,
     * where an empty list is rendered as "nobody is blocked right now". A failure has to leave here as a
     * failure — see {@link ForDetectingIntrusions} for why the breach-attempt sweep is right to want the
     * opposite.
     */
    @Override
    public List<BlockDecision> getBlockDecisions() {
        return forDetectingIntrusions.getActiveDecisionsOrFail();
    }

    // --- LiftBlockUseCase ---

    @Override
    public void liftBlock(String sourceIp) {
        SourceAddress.of(sourceIp).liftBlock(forLiftingBlocks);
    }

    // --- TrustAddressUseCase ---

    /**
     * Both effects, in this order. Persisting first means that if the unblock fails and throws, the address
     * is already trusted from the next CrowdSec restart — the operator's decision is not lost to a
     * transient exec failure, and re-trying costs nothing. Two driven ports, no use case injected: this is
     * orchestration, which is exactly what a service is for.
     */
    @Override
    public void trustAddress(String sourceIp) {
        SourceAddress address = SourceAddress.of(sourceIp);
        address.trust(forPersistingTrustedAddresses);
        // Trusting alone would leave it blocked until the next CrowdSec restart (PRD §6.26), which Vaier
        // deliberately does not trigger — restarting the edge bouncer is the lockout risk #329 names first.
        address.liftBlock(forLiftingBlocks);
    }

    // --- GetTrustedAddressesUseCase ---

    /**
     * The operator's own decisions, straight from the store that holds nothing else (#348). It does not go
     * through {@link #getTrustedNetworks()} on purpose: that assembles the structural entries too, and the
     * screen this feeds hangs an untrust verb off every row it draws.
     */
    @Override
    public List<SourceAddress> getTrustedAddresses() {
        return forPersistingTrustedAddresses.getAll();
    }

    // --- UntrustAddressUseCase ---

    /**
     * One effect, unlike its counterpart above: the decision is forgotten, and nobody is blocked. Vaier
     * never blocks an address, so there is no second half here to mirror {@code trustAddress}'s unban.
     */
    @Override
    public void untrustAddress(String sourceIp) {
        SourceAddress.of(sourceIp).untrust(forPersistingTrustedAddresses);
    }
}
