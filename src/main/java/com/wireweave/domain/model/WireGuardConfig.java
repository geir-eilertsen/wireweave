package com.wireweave.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WireGuardConfig {

    private Peer peer;
    private List<PeerConnection> connections;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PeerConnection {
        private String publicKey;
        private String allowedIps;
        private String endpoint;
        private Integer persistentKeepalive;
    }

    public String toConfigString() {
        StringBuilder sb = new StringBuilder();

        // [Interface] section
        sb.append("[Interface]\n");
        sb.append("PrivateKey = ").append(peer.getPrivateKey()).append("\n");
        sb.append("Address = ").append(peer.getIpAddress()).append("/32\n");
        if (peer.getListenPort() != null) {
            sb.append("ListenPort = ").append(peer.getListenPort()).append("\n");
        }
        sb.append("\n");

        // [Peer] sections
        for (PeerConnection conn : connections) {
            sb.append("[Peer]\n");
            sb.append("PublicKey = ").append(conn.getPublicKey()).append("\n");
            sb.append("AllowedIPs = ").append(conn.getAllowedIps()).append("\n");
            if (conn.getEndpoint() != null) {
                sb.append("Endpoint = ").append(conn.getEndpoint()).append("\n");
            }
            if (conn.getPersistentKeepalive() != null) {
                sb.append("PersistentKeepalive = ").append(conn.getPersistentKeepalive()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
