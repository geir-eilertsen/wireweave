package com.wireweave.infrastructure.persistence;

import com.wireweave.domain.model.Peer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataPeerRepository extends JpaRepository<Peer, Long> {

    Optional<Peer> findByName(String name);

    boolean existsByIpAddress(String ipAddress);

    boolean existsByPublicKey(String publicKey);
}
