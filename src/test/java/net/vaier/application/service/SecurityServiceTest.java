package net.vaier.application.service;

import net.vaier.domain.BlockDecision;
import net.vaier.domain.BlockDecisionsUnreadableException;
import net.vaier.domain.MachineType;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForDetectingIntrusions;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForLiftingBlocks;
import net.vaier.domain.port.ForPersistingTrustedAddresses;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    private SecurityService service() {
        SecurityService service = new SecurityService(peerConfigProvider, forWritingCrowdSecWhitelist,
            forDetectingIntrusions, forLiftingBlocks, forPersistingTrustedAddresses);
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
}
