package net.fjordomatic.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.application.GetVpnClientsUseCase;
import net.fjordomatic.application.ResolveVpnPeerIdUseCase;
import net.fjordomatic.domain.VpnClient;
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
    PeerStatsScheduler scheduler;

    @BeforeEach
    void setUp() {
        vpnClients = mock(GetVpnClientsUseCase.class);
        peerIdResolver = mock(ResolveVpnPeerIdUseCase.class);
        eventPublisher = mock(ForPublishingEvents.class);
        scheduler = new PeerStatsScheduler(vpnClients, peerIdResolver, eventPublisher, new ObjectMapper());
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
}
