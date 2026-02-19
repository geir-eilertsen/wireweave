package com.wireweave.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeshTopology {

    private List<Peer> peers;

    // Map of peer ID to list of connected peer IDs (full mesh = all-to-all)
    private Map<Long, List<Long>> connections;

    public int getPeerCount() {
        return peers != null ? peers.size() : 0;
    }

    public int getConnectionCount() {
        if (connections == null) return 0;
        return connections.values().stream()
                .mapToInt(List::size)
                .sum() / 2; // Divide by 2 since connections are bidirectional
    }

    public boolean isFullMesh() {
        int expectedConnections = peers.size() - 1;
        return connections.values().stream()
                .allMatch(list -> list.size() == expectedConnections);
    }
}
