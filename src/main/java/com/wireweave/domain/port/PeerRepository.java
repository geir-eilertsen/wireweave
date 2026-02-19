package com.wireweave.domain.port;

import com.wireweave.domain.model.Peer;

import java.util.List;
import java.util.Optional;

public interface PeerRepository {

    Peer save(Peer peer);

    Optional<Peer> findById(Long id);

    Optional<Peer> findByName(String name);

    List<Peer> findAll();

    void delete(Long id);

    boolean existsByIpAddress(String ipAddress);

    boolean existsByPublicKey(String publicKey);
}
