package com.wireweave.infrastructure.persistence;

import com.wireweave.domain.model.Peer;
import com.wireweave.domain.port.PeerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaPeerRepository implements PeerRepository {

    private final SpringDataPeerRepository repository;

    @Override
    public Peer save(Peer peer) {
        return repository.save(peer);
    }

    @Override
    public Optional<Peer> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<Peer> findByName(String name) {
        return repository.findByName(name);
    }

    @Override
    public List<Peer> findAll() {
        return repository.findAll();
    }

    @Override
    public void delete(Long id) {
        repository.deleteById(id);
    }

    @Override
    public boolean existsByIpAddress(String ipAddress) {
        return repository.existsByIpAddress(ipAddress);
    }

    @Override
    public boolean existsByPublicKey(String publicKey) {
        return repository.existsByPublicKey(publicKey);
    }
}
