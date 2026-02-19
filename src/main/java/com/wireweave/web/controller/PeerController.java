package com.wireweave.web.controller;

import com.wireweave.application.usecase.CreatePeerUseCase;
import com.wireweave.domain.model.Peer;
import com.wireweave.domain.port.PeerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/peers")
@RequiredArgsConstructor
public class PeerController {

    private final CreatePeerUseCase createPeerUseCase;
    private final PeerRepository peerRepository;

    @PostMapping
    public ResponseEntity<Peer> createPeer(@RequestBody CreatePeerUseCase.CreatePeerRequest request) {
        Peer peer = createPeerUseCase.execute(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(peer);
    }

    @GetMapping
    public ResponseEntity<List<Peer>> getAllPeers() {
        return ResponseEntity.ok(peerRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Peer> getPeer(@PathVariable Long id) {
        return peerRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePeer(@PathVariable Long id) {
        peerRepository.delete(id);
        return ResponseEntity.noContent().build();
    }
}
