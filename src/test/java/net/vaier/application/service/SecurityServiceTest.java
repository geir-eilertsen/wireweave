package net.vaier.application.service;

import net.vaier.domain.AccessSource;
import net.vaier.domain.AccessSources;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.GeoLocation;
import net.vaier.domain.LastServiceReached;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForLiftingBlocks;
import net.vaier.domain.port.ForPersistingAccessSources;
import net.vaier.domain.port.ForPersistingLastServicesReached;
import net.vaier.domain.port.ForPersistingTrustedAddresses;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityServiceTest {

    @Mock ForGettingPeerConfigurations peerConfigProvider;
    @Mock ForWritingCrowdSecWhitelist forWritingCrowdSecWhitelist;
    @Mock ForDetectingIntrusions forDetectingIntrusions;
    @Mock ForLiftingBlocks forLiftingBlocks;
    @Mock ForPersistingTrustedAddresses forPersistingTrustedAddresses;
    @Mock ForPersistingAccessSources forPersistingAccessSources;
    @Mock ForPersistingLastServicesReached forPersistingLastServicesReached;
    @Mock ForGeolocatingIps forGeolocatingIps;

    private SecurityService service() {
        SecurityService service = new SecurityService(peerConfigProvider, forWritingCrowdSecWhitelist,
            forDetectingIntrusions, forLiftingBlocks, forPersistingTrustedAddresses,
            forPersistingAccessSources, forPersistingLastServicesReached, forGeolocatingIps);
        ReflectionTestUtils.setField(service, "vpnSubnet", "10.13.13.0/24");
        ReflectionTestUtils.setField(service, "dockerBridgeCidr", "172.20.0.0/16");
        return service;
    }

    private static PeerConfiguration relay(String id, String lanCidr) {
        return new PeerConfiguration(id, id, "10.13.13.2", "",
            MachineType.UBUNTU_SERVER, lanCidr, null, null);
    }

    @Test
    void refreshTrustedNetworks_assemblesFromConfigAndPeersThenWrites() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "192.168.3.0/24"),
            relay("colina27", "192.168.1.0/24")));

        service().refreshTrustedNetworks();

        ArgumentCaptor<TrustedNetworks> captor = ArgumentCaptor.forClass(TrustedNetworks.class);
        verify(forWritingCrowdSecWhitelist).write(captor.capture());
        assertThat(captor.getValue().allCidrs()).containsExactly(
            "10.13.13.0/24", "172.20.0.0/16", "192.168.3.0/24", "192.168.1.0/24");
    }

    /**
     * The whole reason trusted addresses are stored rather than appended to the whitelist file: this
     * refresh rewrites that file wholesale every five minutes. If it did not read the store, trusting an
     * address would silently expire within five minutes.
     */
    @Test
    void refreshTrustedNetworks_foldsInEveryPermanentlyTrustedAddress() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingTrustedAddresses.getAll())
            .thenReturn(List.of(SourceAddress.of("195.178.110.155")));

        service().refreshTrustedNetworks();

        ArgumentCaptor<TrustedNetworks> captor = ArgumentCaptor.forClass(TrustedNetworks.class);
        verify(forWritingCrowdSecWhitelist).write(captor.capture());
        assertThat(captor.getValue().allCidrs())
            .containsExactly("10.13.13.0/24", "172.20.0.0/16", "195.178.110.155/32");
    }

    /**
     * The breach-attempt sweep needs the same allowlist the whitelist file is rendered from, to tell a
     * ban on a stranger apart from a ban that is locking the operator out of their own fleet. It is read
     * here rather than assembled by the watcher, so the two can never drift apart.
     */
    @Test
    void getTrustedNetworks_assemblesTheSameAllowlistTheWhitelistFileIsRenderedFrom() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(
            relay("apalveien5", "192.168.3.0/24")));
        when(forPersistingTrustedAddresses.getAll())
            .thenReturn(List.of(SourceAddress.of("195.178.110.155")));

        TrustedNetworks trustedNetworks = service().getTrustedNetworks();

        assertThat(trustedNetworks.allCidrs()).containsExactly(
            "10.13.13.0/24", "172.20.0.0/16", "192.168.3.0/24", "195.178.110.155/32");
    }

    /** Reading the allowlist must not rewrite CrowdSec's file as a side effect. */
    @Test
    void getTrustedNetworks_writesNothing() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of());

        service().getTrustedNetworks();

        verifyNoInteractions(forWritingCrowdSecWhitelist);
    }

    @Test
    void getBlockDecisions_readsTheActiveDecisions() {
        BlockDecision decision = BlockDecision.builder().id(1L).sourceIp("1.2.3.4").type("ban").build();
        when(forDetectingIntrusions.getActiveDecisionsOrFail()).thenReturn(List.of(decision));

        assertThat(service().getBlockDecisions()).containsExactly(decision);
    }

    /**
     * This use case feeds the operator's security screen, where an empty list is rendered as "nobody is
     * blocked right now". So it takes the loud read, never the sweep's silent one: a failure has to travel
     * out of here as a failure. The silent read is stubbed with an all-clear precisely so that using it
     * would look like success — which is exactly how this shipped, and how it read on screen.
     */
    @Test
    void getBlockDecisions_whenCrowdSecCannotBeAsked_failsRatherThanReadingAsNothingBlocked() {
        when(forDetectingIntrusions.getActiveDecisionsOrFail())
            .thenThrow(new BlockDecisionsUnreadableException("Vaier could not read who CrowdSec is blocking"));

        assertThatThrownBy(() -> service().getBlockDecisions())
            .isInstanceOf(BlockDecisionsUnreadableException.class);

        verify(forDetectingIntrusions, never()).getActiveDecisionsOrEmpty();
    }

    @Test
    void liftBlock_liftsTheBlockOnThatAddress() {
        service().liftBlock("195.178.110.155");

        verify(forLiftingBlocks).liftBlock(SourceAddress.of("195.178.110.155"));
    }

    @Test
    void liftBlock_rejectsAnAddressThatIsNotAnIpv4Address() {
        assertThatThrownBy(() -> service().liftBlock("1.2.3.4; rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(forLiftingBlocks);
    }

    /**
     * Trusting is two effects and needs both. Persisting alone would leave the address banned right now,
     * because CrowdSec re-reads its whitelist parser only when it restarts (PRD §6.26); lifting alone would
     * let the address be re-banned by the next scenario that matches it.
     */
    @Test
    void trustAddress_persistsTheAddressAndLetsItBackInNow() {
        service().trustAddress("195.178.110.155");

        SourceAddress address = SourceAddress.of("195.178.110.155");
        InOrder inOrder = inOrder(forPersistingTrustedAddresses, forLiftingBlocks);
        inOrder.verify(forPersistingTrustedAddresses).save(address);
        inOrder.verify(forLiftingBlocks).liftBlock(address);
    }

    @Test
    void trustAddress_rejectsAnAddressThatIsNotAnIpv4Address() {
        assertThatThrownBy(() -> service().trustAddress("evil.example.com"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(forPersistingTrustedAddresses);
        verifyNoInteractions(forLiftingBlocks);
    }

    // --- reading and undoing the operator's own trust decisions (#348) --------------------------------

    /**
     * Deliberately the store's contents and nothing else. The structural trusted networks — the VPN subnet,
     * the Docker bridge, every relay's LAN — are assembled into {@code TrustedNetworks} for the whitelist
     * file, and they must never reach the list the operator is offered an untrust verb next to. This read
     * has no access to them at all, which is why it cannot show one by accident.
     */
    @Test
    void getTrustedAddresses_returnsOnlyWhatTheOperatorTrustedByHand() {
        when(forPersistingTrustedAddresses.getAll())
            .thenReturn(List.of(SourceAddress.of("8.8.8.8"), SourceAddress.of("1.1.1.1")));

        assertThat(service().getTrustedAddresses())
            .containsExactly(SourceAddress.of("8.8.8.8"), SourceAddress.of("1.1.1.1"));

        verifyNoInteractions(peerConfigProvider);
    }

    /**
     * Untrusting removes the operator's decision and nothing else. In particular it does <em>not</em> block
     * the address: Vaier never blocks anyone — CrowdSec's own scenarios decide that — so an untrusted
     * address is simply back to being judged on its behaviour.
     */
    @Test
    void untrustAddress_removesTheAddressAndBlocksNobody() {
        service().untrustAddress("195.178.110.155");

        verify(forPersistingTrustedAddresses).delete(SourceAddress.of("195.178.110.155"));
        verifyNoInteractions(forLiftingBlocks);
        verifyNoInteractions(forWritingCrowdSecWhitelist);
    }

    /**
     * The guarantee that survives the prefix rule failing. A relay whose LAN is a single host is nameable
     * here — {@code SourceAddress.of("192.168.9.9")} is perfectly valid — so if the {@code /32} refusal were
     * the only thing standing between the untrust verb and a structural network, this would be the hole.
     * It is not: the structural entries are assembled from the VPN subnet, the Docker bridge and the peer
     * configurations, and the trust store cannot write any of them. Untrusting leaves all three untouched.
     */
    @Test
    void untrustAddress_cannotRemoveAStructuralNetworkEvenWhenItsAddressIsNameable() {
        when(peerConfigProvider.getAllPeerConfigs()).thenReturn(List.of(relay("colina27", "192.168.9.9/32")));

        service().untrustAddress("192.168.9.9");

        assertThat(service().getTrustedNetworks().allCidrs())
            .as("the structural entries do not come from the store, so an untrust cannot reach them")
            .contains("10.13.13.0/24", "172.20.0.0/16", "192.168.9.9/32");
    }

    @Test
    void untrustAddress_rejectsAnAddressThatIsNotAnIpv4Address() {
        assertThatThrownBy(() -> service().untrustAddress("1.2.3.4; rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(forPersistingTrustedAddresses);
    }

    /**
     * The same guard {@code SourceAddress} enforces, asserted where an operator's request actually arrives:
     * a structural trusted network is a prefix, not a host, so it cannot even be named to this use case.
     * There is no code path from the untrust verb to the VPN subnet.
     */
    @Test
    void untrustAddress_cannotBeAskedToRemoveAStructuralTrustedNetwork() {
        assertThatThrownBy(() -> service().untrustAddress("10.13.13.0/24"))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(forPersistingTrustedAddresses);
    }

    // --- Access sources: where allowed accesses came from ---

    /** Relative, not a literal date: these tests exercise a one-month retention rule. */
    private static final Instant RECENTLY = Instant.now();
    private static final GeoLocation OSLO = new GeoLocation(59.91, 10.75, "Oslo", "Norway");

    @Test
    void recordAllowedAccess_countsTheAccessAgainstThePlaceItCameFrom() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();

        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY.plusSeconds(1));

        assertThat(service.getAccessSources()).singleElement().satisfies(source -> {
            assertThat(source.city()).isEqualTo("Oslo");
            assertThat(source.count()).isEqualTo(2);
            assertThat(source.people()).containsExactly("geir@example.com");
        });
    }

    /**
     * This runs inside the forward-auth check for every request to every gated service. Touching the disk
     * there would put a file write on the critical path of authenticating a page load — the flush is what
     * the disk is for.
     */
    @Test
    void recordAllowedAccess_neverWritesToDisk() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));

        service().recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        verify(forPersistingAccessSources, never()).save(any());
    }

    /**
     * A broken map must never cost anybody access to their own services. The controller guards this call
     * too; the use case guarantees it as well, because the property is worth being true twice.
     */
    @Test
    void recordAllowedAccess_neverThrowsWhenTheGeolocationLookupBlowsUp() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenThrow(new IllegalStateException("mmdb closed"));
        SecurityService service = service();

        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        assertThat(service.getAccessSources()).singleElement()
            .satisfies(source -> assertThat(source.locatable()).isFalse());
    }

    @Test
    void getAccessSources_startsFromWhatSurvivedTheLastRestart() {
        when(forPersistingAccessSources.getAll()).thenReturn(AccessSources.of(List.of(
            AccessSource.builder().city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
                .count(412).firstSeen(RECENTLY.minusSeconds(86400)).lastSeen(RECENTLY).people(List.of()).build())));
        SecurityService service = service();
        service.onApplicationReady(null);

        assertThat(service.getAccessSources()).singleElement()
            .satisfies(source -> assertThat(source.count()).isEqualTo(412));
    }

    /** A store that cannot be read costs the history, never the recording that follows it. */
    @Test
    void recordAllowedAccess_stillWorksWhenTheStoreCouldNotBeRead() {
        when(forPersistingAccessSources.getAll()).thenThrow(new IllegalStateException("unreadable"));
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.onApplicationReady(null);

        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        assertThat(service.getAccessSources()).hasSize(1);
    }

    @Test
    void flushAccessSources_savesWhatHasBeenRecordedSinceTheLastFlush() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        Optional<List<AccessSource>> flushed = service.flushAccessSources();

        ArgumentCaptor<AccessSources> captor = ArgumentCaptor.forClass(AccessSources.class);
        verify(forPersistingAccessSources).save(captor.capture());
        assertThat(captor.getValue().sources()).extracting(AccessSource::city).containsExactly("Oslo");
        assertThat(flushed).hasValueSatisfying(sources ->
            assertThat(sources).extracting(AccessSource::city).containsExactly("Oslo"));
    }

    /**
     * One month of retention, applied where the file is written, so the store cannot grow forever. Forgetting
     * a place is a change like any other: the file has to be written even though nobody has been anywhere.
     */
    @Test
    void flushAccessSources_forgetsAPlaceNobodyHasComeFromInAMonth() {
        when(forPersistingAccessSources.getAll()).thenReturn(AccessSources.of(List.of(
            AccessSource.builder().city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
                .count(412)
                .firstSeen(Instant.now().minus(AccessSource.RETENTION).minusSeconds(172800))
                .lastSeen(Instant.now().minus(AccessSource.RETENTION).minusSeconds(86400))
                .people(List.of()).build())));
        SecurityService service = service();
        service.onApplicationReady(null);

        assertThat(service.flushAccessSources()).contains(List.of());

        ArgumentCaptor<AccessSources> captor = ArgumentCaptor.forClass(AccessSources.class);
        verify(forPersistingAccessSources).save(captor.capture());
        assertThat(captor.getValue().sources()).isEmpty();
    }

    // --- Last service reached: what the machine on the tunnel opened ---

    private static final MachineId PHONE = TestMachineIds.of("phone");

    private static PeerConfiguration phoneAt(String tunnelIp) {
        return new PeerConfiguration("phone", "phone", tunnelIp, "", MachineType.MOBILE_CLIENT, null,
            null, null, null, null, PHONE);
    }

    /**
     * The whole feature: a request on the tunnel was authenticated by WireGuard, so its caller address is
     * the peer — and only then does Vaier know which device opened which service.
     */
    @Test
    void recordAllowedAccess_recordsWhatTheMachineOnTheTunnelReached() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.4"))
            .thenReturn(Optional.of(phoneAt("10.13.13.4")));

        service().recordAllowedAccess("10.13.13.4", "geir@example.com", "grafana.example.com", RECENTLY);

        ArgumentCaptor<LastServiceReached> captor = ArgumentCaptor.forClass(LastServiceReached.class);
        verify(forPersistingLastServicesReached).save(captor.capture());
        assertThat(captor.getValue())
            .isEqualTo(new LastServiceReached(PHONE, "grafana.example.com", RECENTLY));
    }

    /**
     * A carrier address is shared by thousands of subscribers: it identifies a person, never a device. The
     * access still counts towards its place — it just says nothing about which machine made it.
     */
    @Test
    void recordAllowedAccess_attributesNoServiceToAnyMachineWhenTheCallerIsNotOnTheTunnel() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();

        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "grafana.example.com", RECENTLY);

        verifyNoInteractions(forPersistingLastServicesReached);
        assertThat(service.getAccessSources()).hasSize(1);
    }

    /** Same guarantee as the map's: a store that blows up costs a statistic, never anybody's access. */
    @Test
    void recordAllowedAccess_neverThrowsWhenTheReachStoreBlowsUp() {
        when(peerConfigProvider.getPeerConfigByIp("10.13.13.4"))
            .thenReturn(Optional.of(phoneAt("10.13.13.4")));
        doThrow(new IllegalStateException("store is on fire"))
            .when(forPersistingLastServicesReached).save(any());
        SecurityService service = service();

        assertThatCode(() -> service.recordAllowedAccess("10.13.13.4", "geir@example.com",
            "grafana.example.com", RECENTLY)).doesNotThrowAnyException();
    }

    @Test
    void flushLastServicesReached_writesThroughToTheStore() {
        service().flushLastServicesReached();

        verify(forPersistingLastServicesReached).flush();
    }

    /**
     * The flush runs every minute forever. On a fleet nobody is using, an unconditional write is ~1440
     * identical files a day — and an SSE push behind each one, repainting the map's green dots for nothing.
     */
    @Test
    void flushAccessSources_whenNothingHasChangedSinceTheLastSave_writesNothing() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);
        service.flushAccessSources();

        service.flushAccessSources();

        verify(forPersistingAccessSources, times(1)).save(any());
    }

    /**
     * Not writing is only half of it: the caller pushes what it gets over SSE, so a flush that wrote nothing
     * has to say so rather than hand back a payload the browser would repaint the map for.
     */
    @Test
    void flushAccessSources_whenNothingHasChangedSinceTheLastSave_hasNothingToHandTheCaller() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);
        service.flushAccessSources();

        Optional<List<AccessSource>> flushed = service.flushAccessSources();

        assertThat(flushed).isEmpty();
    }

    /**
     * A write that blew up left the store holding something older, so the next flush must try again — the
     * collection counts as clean only once a save has actually succeeded.
     */
    @Test
    void flushAccessSources_whenTheSaveFailed_writesAgainOnTheNextFlush() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        doThrow(new IllegalStateException("disk full")).doNothing()
            .when(forPersistingAccessSources).save(any());
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);
        assertThatThrownBy(service::flushAccessSources).isInstanceOf(IllegalStateException.class);

        Optional<List<AccessSource>> flushed = service.flushAccessSources();

        assertThat(flushed).isPresent();
        verify(forPersistingAccessSources, times(2)).save(any());
    }

    /**
     * The flush writes a file; recording an allowed access runs inside the forward-auth check for every
     * request to every gated service. If the write happened under the same monitor recording needs, then
     * once a minute every concurrent request across the fleet would queue behind a YAML write.
     */
    @Test
    void flushAccessSources_doesNotHoldUpTheForwardAuthPathWhileTheStoreIsBeingWritten() throws Exception {
        when(forGeolocatingIps.locate(any())).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        CountDownLatch saveInFlight = new CountDownLatch(1);
        CountDownLatch releaseSave = new CountDownLatch(1);
        doAnswer(invocation -> {
            saveInFlight.countDown();
            releaseSave.await(5, TimeUnit.SECONDS);
            return null;
        }).when(forPersistingAccessSources).save(any());

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> flush = threads.submit(service::flushAccessSources);
            assertThat(saveInFlight.await(5, TimeUnit.SECONDS)).as("the save started").isTrue();
            Future<?> record = threads.submit(() ->
                service.recordAllowedAccess("198.51.100.9", "someone@example.com", "plex.example.com", RECENTLY));

            assertThatCode(() -> record.get(2, TimeUnit.SECONDS))
                .as("an allowed access is recorded while the store is still being written")
                .doesNotThrowAnyException();

            releaseSave.countDown();
            flush.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSave.countDown();
            threads.shutdownNow();
        }
    }

    /**
     * Dropping the monitor across the write buys the forward-auth path its speed, but it left flush racing
     * flush: {@code fixedDelay} only stops the scheduler overlapping itself, and {@code @PreDestroy}'s
     * shutdown flush is a second caller. Interleaved, the older snapshot is written last and marked clean —
     * which during normal running the next minute heals, and at shutdown nothing does, defeating the whole
     * point of flushing on the way out.
     */
    @Test
    void flushAccessSources_twoAtOnce_neverLeaveTheOlderSnapshotInTheStore() throws Exception {
        when(forGeolocatingIps.locate(any())).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        CountDownLatch firstSaveInFlight = new CountDownLatch(1);
        CountDownLatch releaseFirstSave = new CountDownLatch(1);
        AtomicBoolean firstSave = new AtomicBoolean(true);
        List<Long> savedCounts = Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            AccessSources saving = invocation.getArgument(0);
            if (firstSave.compareAndSet(true, false)) {
                firstSaveInFlight.countDown();
                releaseFirstSave.await(5, TimeUnit.SECONDS);
            }
            savedCounts.add(saving.sources().get(0).count());
            return null;
        }).when(forPersistingAccessSources).save(any());

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> scheduled = threads.submit(service::flushAccessSources);
            assertThat(firstSaveInFlight.await(5, TimeUnit.SECONDS)).as("the first save started").isTrue();

            service.recordAllowedAccess("198.51.100.9", "kari@example.com", "plex.example.com", RECENTLY);
            Future<?> shutdown = threads.submit(service::flushAccessSources);
            // Bounded, so a regression fails here rather than hanging: every chance to overtake the first
            // save, and then the assertion that it did not.
            Thread.sleep(200);

            releaseFirstSave.countDown();
            scheduled.get(5, TimeUnit.SECONDS);
            shutdown.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirstSave.countDown();
            threads.shutdownNow();
        }

        assertThat(savedCounts).as("the write that landed last is the newer snapshot").last().isEqualTo(2L);
        assertThat(service.flushAccessSources())
            .as("and what was marked clean is what the store actually holds").isEmpty();
    }

    /** Prune once, not twice: the flush must leave behind exactly what it wrote. */
    @Test
    void flushAccessSources_leavesTheServiceHoldingWhatItSaved() {
        when(forGeolocatingIps.locate("203.0.113.7")).thenReturn(Optional.of(OSLO));
        SecurityService service = service();
        service.recordAllowedAccess("203.0.113.7", "geir@example.com", "plex.example.com", RECENTLY);

        service.flushAccessSources();

        assertThat(service.getAccessSources()).extracting(AccessSource::city).containsExactly("Oslo");
    }
}
