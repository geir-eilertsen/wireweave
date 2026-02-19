package com.wireweave.application.usecase;

import com.wireweave.domain.model.MeshTopology;
import com.wireweave.domain.model.Peer;
import com.wireweave.domain.model.WireGuardConfig;
import com.wireweave.domain.port.ConfigurationGenerator;
import com.wireweave.domain.port.PeerRepository;
import com.wireweave.domain.service.MeshTopologyGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class GenerateMeshConfigUseCase {

    private final PeerRepository peerRepository;
    private final MeshTopologyGenerator meshTopologyGenerator;
    private final ConfigurationGenerator configurationGenerator;

    public Map<String, WireGuardConfig> execute() {
        log.info("Generating mesh network configurations");

        // Get all peers
        List<Peer> peers = peerRepository.findAll();
        if (peers.isEmpty()) {
            throw new IllegalStateException("No peers available to generate mesh configuration");
        }

        // Generate mesh topology
        MeshTopology topology = meshTopologyGenerator.generateFullMesh(peers);

        // Generate configuration for each peer
        Map<String, WireGuardConfig> configs = new HashMap<>();
        for (Peer peer : peers) {
            WireGuardConfig config = configurationGenerator.generateConfig(peer, topology);
            configs.put(peer.getName(), config);
        }

        log.info("Generated configurations for {} peers", configs.size());
        return configs;
    }
}
