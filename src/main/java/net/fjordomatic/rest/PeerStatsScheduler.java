package net.fjordomatic.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.application.GetVpnClientsUseCase;
import net.fjordomatic.application.ResolveVpnPeerIdUseCase;
import net.fjordomatic.domain.VpnClient;
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

    @Scheduled(fixedDelay = 10000)
    public void publishPeerStats() {
        try {
            List<VpnClient> clients = vpnClients.getClients();
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
}
