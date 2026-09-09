package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.application.GetVpnClientsUseCase;
import net.vaier.application.ResolveVpnPeerIdUseCase;
import net.vaier.application.PublishedServicesCacheInvalidator;
import net.vaier.domain.PeerLiveness;
import net.vaier.domain.VpnClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PeerStatsScheduler {

    private final GetVpnClientsUseCase vpnClients;
    private final ResolveVpnPeerIdUseCase peerIdResolver;
    private final ForPublishingEvents eventPublisher;
    private final ObjectMapper objectMapper;
    private final PublishedServicesCacheInvalidator publishedServicesCacheInvalidator;
    private PeerLiveness lastLiveness;

    @Scheduled(fixedDelay = 10000)
    public void publishPeerStats() {
        try {
            List<VpnClient> clients = vpnClients.getClients();
            noticeLivenessChange(clients);
            List<Map<String, Object>> stats = clients.stream()
                    .map(client -> {
                        String peerIp = client.vpnIp();
                        String peerId = peerIdResolver.resolvePeerIdByIp(peerIp);
                        return Map.<String, Object>of(
                                "name", peerId != null ? peerId : peerIp,
                                "latestHandshake", client.latestHandshake(),
                                "connected", client.isConnected(),
                                "transferRx", client.transferRx(),
                                "transferTx", client.transferTx(),
                                "endpointIp", client.endpointIp() != null ? client.endpointIp() : "",
                                "endpointPort", client.endpointPort() != null ? client.endpointPort() : ""
                        );
                    })
                    .toList();
            eventPublisher.publish("vpn-peers", "peers-stats", objectMapper.writeValueAsString(stats));
        } catch (Exception e) {
            log.debug("Failed to publish peer stats via SSE: {}", e.getMessage());
        }
    }

    // The published-services list is cached and rebuilt on route edits and local container events, but a
    // peer's tunnel coming up or going away is what decides whether its services are reachable — and
    // nothing else on that path would ever notice. This tick already reads every peer, so it tells.
    private void noticeLivenessChange(List<VpnClient> clients) {
        PeerLiveness now = PeerLiveness.of(clients);
        if (lastLiveness != null && now.differsFrom(lastLiveness)) {
            publishedServicesCacheInvalidator.invalidatePublishedServicesCache();
            eventPublisher.publish("published-services", "service-updated", "peer-liveness-changed");
        }
        lastLiveness = now;
    }
}
