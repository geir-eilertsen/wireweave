package com.wireweave.application.usecase;

import com.wireweave.domain.model.Peer;
import com.wireweave.domain.port.KeyPairGenerator;
import com.wireweave.domain.port.PeerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreatePeerUseCase {

    private final PeerRepository peerRepository;
    private final KeyPairGenerator keyPairGenerator;

    @Transactional
    public Peer execute(CreatePeerRequest request) {
        log.info("Creating peer: {}", request.name());

        // Validate IP address is unique
        if (peerRepository.existsByIpAddress(request.ipAddress())) {
            throw new IllegalArgumentException("IP address already in use: " + request.ipAddress());
        }

        // Generate key pair
        KeyPairGenerator.KeyPair keyPair = keyPairGenerator.generate();

        // Create peer
        Peer peer = Peer.builder()
                .name(request.name())
                .publicKey(keyPair.publicKey())
                .privateKey(keyPair.privateKey())
                .ipAddress(request.ipAddress())
                .listenPort(request.listenPort())
                .endpoint(request.endpoint())
                .build();

        Peer savedPeer = peerRepository.save(peer);
        log.info("Created peer with ID: {}", savedPeer.getId());

        return savedPeer;
    }

    public record CreatePeerRequest(
            String name,
            String ipAddress,
            Integer listenPort,
            String endpoint
    ) {}
}
