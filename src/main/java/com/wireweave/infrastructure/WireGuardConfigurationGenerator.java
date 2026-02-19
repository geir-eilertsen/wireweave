package com.wireweave.infrastructure;

import com.wireweave.domain.model.MeshTopology;
import com.wireweave.domain.model.Peer;
import com.wireweave.domain.model.WireGuardConfig;
import com.wireweave.domain.port.ConfigurationGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class WireGuardConfigurationGenerator implements ConfigurationGenerator {

    private static final int DEFAULT_PERSISTENT_KEEPALIVE = 25;

    @Override
    public WireGuardConfig generateConfig(Peer peer, MeshTopology topology) {
        log.debug("Generating config for peer: {}", peer.getName());

        List<Long> connectedPeerIds = topology.getConnections().get(peer.getId());
        if (connectedPeerIds == null) {
            connectedPeerIds = List.of();
        }

        List<WireGuardConfig.PeerConnection> connections = new ArrayList<>();

        for (Long connectedPeerId : connectedPeerIds) {
            Peer connectedPeer = topology.getPeers().stream()
                    .filter(p -> p.getId().equals(connectedPeerId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Peer not found: " + connectedPeerId));

            WireGuardConfig.PeerConnection connection = WireGuardConfig.PeerConnection.builder()
                    .publicKey(connectedPeer.getPublicKey())
                    .allowedIps(connectedPeer.getIpAddress() + "/32")
                    .endpoint(connectedPeer.getEndpoint())
                    .persistentKeepalive(DEFAULT_PERSISTENT_KEEPALIVE)
                    .build();

            connections.add(connection);
        }

        return WireGuardConfig.builder()
                .peer(peer)
                .connections(connections)
                .build();
    }
}
