package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.application.ResolveVpnPeerIdUseCase;
import net.vaier.domain.VpnClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class PeerStatsSchedulerTest {

    GetVpnClientsUseCase vpnClients;
    ResolveVpnPeerIdUseCase peerIdResolver;
    ForPublishingEvents eventPublisher;
    PublishedServicesCacheInvalidator cacheInvalidator;
    PeerStatsScheduler scheduler;

    @BeforeEach
    void setUp() {
        vpnClients = mock(GetVpnClientsUseCase.class);
        peerIdResolver = mock(ResolveVpnPeerIdUseCase.class);
        eventPublisher = mock(ForPublishingEvents.class);
        cacheInvalidator = mock(PublishedServicesCacheInvalidator.class);
        scheduler = new PeerStatsScheduler(vpnClients, peerIdResolver, eventPublisher, new ObjectMapper(),
            cacheInvalidator);
    }

    @Test
    void publishPeerStats_publishesPeersStatsEvent() {
        when(vpnClients.getClients()).thenReturn(List.of(
                new VpnClient("pubkey1", "10.0.0.2/32", "1.2.3.4", "51820", "1700000000", "1024", "2048")
        ));
        when(peerIdResolver.resolvePeerIdByIp("10.0.0.2")).thenReturn("myserver");

        scheduler.publishPeerStats();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("peers-stats"), contains("myserver"));
    }

    @Test
    void publishPeerStats_includesServerComputedConnectedFlag() {
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 30);
        when(vpnClients.getClients()).thenReturn(List.of(
                new VpnClient("pubkey1", "10.0.0.2/32", "1.2.3.4", "51820", recent, "1024", "2048")
        ));
        when(peerIdResolver.resolvePeerIdByIp("10.0.0.2")).thenReturn("myserver");

        scheduler.publishPeerStats();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("peers-stats"), contains("\"connected\":true"));
    }

    @Test
    void publishPeerStats_staleHandshakePeerReportedDisconnected() {
        when(vpnClients.getClients()).thenReturn(List.of(
                new VpnClient("pubkey1", "10.0.0.2/32", "1.2.3.4", "51820", "0", "1024", "2048")
        ));
        when(peerIdResolver.resolvePeerIdByIp("10.0.0.2")).thenReturn("myserver");

        scheduler.publishPeerStats();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("peers-stats"), contains("\"connected\":false"));
    }

    @Test
    void publishPeerStats_whenFetchFails_doesNotThrow() {
        when(vpnClients.getClients()).thenThrow(new RuntimeException("wg unavailable"));

        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.publishPeerStats())
                .doesNotThrowAnyException();
    }

    @Test
    void publishPeerStats_noPeers_publishesEmptyArray() {
        when(vpnClients.getClients()).thenReturn(List.of());

        scheduler.publishPeerStats();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("peers-stats"), eq("[]"));
    }

    // --- a peer flipping rebuilds the published-services list (the frozen Colina tiles) ---

    @Test
    void publishPeerStats_aPeerComingUp_rebuildsThePublishedServicesListAndSaysSo() {
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 30);
        when(vpnClients.getClients())
            .thenReturn(List.of(new VpnClient("colina", "10.13.13.3/32", "1.2.3.4", "51820", "0", "1", "1")))
            .thenReturn(List.of(new VpnClient("colina", "10.13.13.3/32", "1.2.3.4", "51820", recent, "2", "2")));

        scheduler.publishPeerStats();
        scheduler.publishPeerStats();

        verify(cacheInvalidator, times(1)).invalidatePublishedServicesCache();
        verify(eventPublisher).publish(eq("published-services"), eq("service-updated"), eq("peer-liveness-changed"));
    }

    @Test
    void publishPeerStats_trafficWithoutAFlip_leavesTheListAlone() {
        String recent = String.valueOf(System.currentTimeMillis() / 1000 - 30);
        when(vpnClients.getClients())
            .thenReturn(List.of(new VpnClient("colina", "10.13.13.3/32", "1.2.3.4", "51820", recent, "1", "1")))
            .thenReturn(List.of(new VpnClient("colina", "10.13.13.3/32", "1.2.3.4", "51820", recent, "900", "900")));

        scheduler.publishPeerStats();
        scheduler.publishPeerStats();

        verify(cacheInvalidator, never()).invalidatePublishedServicesCache();
        verify(eventPublisher, never()).publish(eq("published-services"), anyString(), anyString());
    }
}
