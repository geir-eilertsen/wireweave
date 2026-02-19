package com.wireweave.domain.service;

import com.wireweave.domain.model.MeshTopology;
import com.wireweave.domain.model.Peer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MeshTopologyGenerator {

    /**
     * Generates a full mesh topology where every peer connects to every other peer.
     */
    public MeshTopology generateFullMesh(List<Peer> peers) {
        log.info("Generating full mesh topology for {} peers", peers.size());

        Map<Long, List<Long>> connections = new HashMap<>();

        for (Peer peer : peers) {
            // Connect to all other peers
            List<Long> connectedPeers = peers.stream()
                    .filter(p -> !p.getId().equals(peer.getId()))
                    .map(Peer::getId)
                    .collect(Collectors.toList());

            connections.put(peer.getId(), connectedPeers);
        }

        MeshTopology topology = MeshTopology.builder()
                .peers(peers)
                .connections(connections)
                .build();

        log.info("Generated mesh topology with {} peers and {} connections",
                topology.getPeerCount(), topology.getConnectionCount());

        return topology;
    }
}
