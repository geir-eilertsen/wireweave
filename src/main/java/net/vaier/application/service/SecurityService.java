package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.FlushAccessSourcesUseCase;
import net.vaier.application.GetAccessSourcesUseCase;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetTrustedAddressesUseCase;
import net.vaier.application.GetTrustedNetworksUseCase;
import net.vaier.application.LiftBlockUseCase;
import net.vaier.application.RecordAllowedAccessUseCase;
import net.vaier.application.RefreshTrustedNetworksUseCase;
import net.vaier.application.TrustAddressUseCase;
import net.vaier.application.UntrustAddressUseCase;
import net.vaier.domain.AccessSource;
import net.vaier.domain.AccessSources;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForLiftingBlocks;
import net.vaier.domain.port.ForPersistingAccessSources;
import net.vaier.domain.port.ForPersistingTrustedAddresses;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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
 *
 * <p>It also owns the other side of the same coin: the {@link AccessSource}s, the places Vaier's own
 * forward-auth check has let people in from. Same domain (who reaches this fleet, and from where), same SSE
 * topic, same screen — so the same service, per the one-service-per-domain rule, rather than a second one.
 */
@Service
@Slf4j
public class SecurityService implements RefreshTrustedNetworksUseCase, GetTrustedNetworksUseCase,
    GetBlockDecisionsUseCase, LiftBlockUseCase, TrustAddressUseCase, GetTrustedAddressesUseCase,
    UntrustAddressUseCase, RecordAllowedAccessUseCase, GetAccessSourcesUseCase, FlushAccessSourcesUseCase {

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
    private final ForPersistingAccessSources forPersistingAccessSources;
    private final ForGeolocatingIps forGeolocatingIps;

    /**
     * The access sources as they stand right now, held in memory between flushes so that recording one
     * never costs a file write on the forward-auth path. Guarded by {@code this}: every read and every
     * swap below is {@code synchronized}, which is what makes a concurrent burst of requests count as
     * many accesses rather than one.
     */
    private AccessSources accessSources = AccessSources.empty();

    /**
     * What the store holds, as far as this instance knows: the last collection a save actually succeeded
     * with, or what was read at boot. Kept so a flush can tell an idle minute from a busy one — the
     * comparison is {@link AccessSources}'s own value equality, not a dirty flag somebody has to remember
     * to set on every path that changes the collection.
     */
    private AccessSources savedAccessSources = AccessSources.empty();

    /**
     * Serialises flush against flush, and deliberately not {@code this}: recording an allowed access must
     * never park behind a file write. {@code fixedDelay} only stops the scheduler overlapping itself, and
     * the shutdown flush is a second caller — interleaved, the older snapshot could be written last and
     * marked clean. During normal running the next minute heals that; at shutdown there is no next minute.
     */
    private final Object flushLock = new Object();

    public SecurityService(ForGettingPeerConfigurations peerConfigProvider,
                           ForWritingCrowdSecWhitelist forWritingCrowdSecWhitelist,
                           ForDetectingIntrusions forDetectingIntrusions,
                           ForLiftingBlocks forLiftingBlocks,
                           ForPersistingTrustedAddresses forPersistingTrustedAddresses,
                           ForPersistingAccessSources forPersistingAccessSources,
                           ForGeolocatingIps forGeolocatingIps) {
        this.peerConfigProvider = peerConfigProvider;
        this.forWritingCrowdSecWhitelist = forWritingCrowdSecWhitelist;
        this.forDetectingIntrusions = forDetectingIntrusions;
        this.forLiftingBlocks = forLiftingBlocks;
        this.forPersistingTrustedAddresses = forPersistingTrustedAddresses;
        this.forPersistingAccessSources = forPersistingAccessSources;
        this.forGeolocatingIps = forGeolocatingIps;
    }

    /**
     * The access sources are read first, and in their own guard: the whitelist refresh below writes to a
     * file CrowdSec owns and is the more likely of the two to fail, and a boot that lost the counts because
     * of it would silently reset a month of history.
     */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        loadAccessSources();
        refreshTrustedNetworks();
    }

    private synchronized void loadAccessSources() {
        try {
            accessSources = forPersistingAccessSources.getAll();
            savedAccessSources = accessSources;
        } catch (Exception e) {
            // The history is a statistic. Losing it costs a month of dots on a map; refusing to start would
            // cost the operator their fleet.
            log.warn("Starting with no access-source history — the store could not be read: {}",
                e.getMessage());
        }
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

    // --- RecordAllowedAccessUseCase ---

    /**
     * In memory only, and it swallows everything. Both are deliberate and both are the contract written on
     * {@link RecordAllowedAccessUseCase}: this runs inside the forward-auth check for every request to
     * every gated service, so a file write here would sit on the critical path of every page load, and an
     * exception here would turn a broken map into a locked door. The caller guards it as well — the
     * property is worth being true twice.
     *
     * <p>Which place the access belongs to, and whether it can be placed at all, are
     * {@link AccessSources#recording}'s decisions; the geolocation port is passed in, not consulted here.
     */
    @Override
    public synchronized void recordAllowedAccess(String callerIp, String person, Instant at) {
        try {
            accessSources = accessSources.recording(callerIp, person, at, forGeolocatingIps);
        } catch (Exception e) {
            log.debug("Not recording this allowed access: {}", e.getMessage());
        }
    }

    // --- GetAccessSourcesUseCase ---

    /**
     * Pruned on the way out, so a place that went quiet stops being drawn the moment it expires rather than
     * at the next flush. The read does not itself forget anything — {@link #flushAccessSources()} is where
     * that becomes permanent.
     */
    @Override
    public synchronized List<AccessSource> getAccessSources() {
        return accessSources.pruned(Instant.now()).sources();
    }

    // --- FlushAccessSourcesUseCase ---

    /**
     * Prune, save, and hand back exactly what was saved — or nothing at all, when the collection is already
     * what the store holds. The pruned collection is kept, not discarded: pruning twice on the same tick
     * would be harmless, but holding the unpruned one would mean the next reader saw places this flush had
     * already decided to forget. A prune that dropped a place is itself a change, and gets written.
     *
     * <p>Two monitors, and which one guards what is the point. {@code this} is held only for the prune and
     * the snapshot — never across the write, because the same monitor guards {@link #recordAllowedAccess}
     * and saving under it would park every concurrent forward-auth check in the fleet behind a YAML write,
     * once a minute. {@link #flushLock} is held across the whole of it, so a second flusher cannot snapshot
     * a newer collection, save it, and then be overwritten by this one's older snapshot.
     */
    @Override
    public Optional<List<AccessSource>> flushAccessSources() {
        synchronized (flushLock) {
            AccessSources snapshot;
            synchronized (this) {
                accessSources = accessSources.pruned(Instant.now());
                snapshot = accessSources;
                if (snapshot.equals(savedAccessSources)) return Optional.empty();
            }
            // Only a save that returned marks the collection clean; a throw leaves the next flush to retry.
            forPersistingAccessSources.save(snapshot);
            synchronized (this) {
                savedAccessSources = snapshot;
            }
            return Optional.of(snapshot.sources());
        }
    }
}
