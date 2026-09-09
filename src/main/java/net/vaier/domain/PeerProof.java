package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * What a phone presents when it wants to {@code leave} the fleet: its own public key and the
 * preshared key it was handed at approval. Together they prove it holds that peer's config, which
 * is the only thing a phone with no session can offer — and it is a secret Vaier already has, in
 * the peer's config on disk, so leaving needs no new secret and nothing new persisted.
 *
 * <p>Leaving this way is offered only to a peer with a {@code Device-held key}. A peer whose keypair
 * Vaier minted had its config handed over as a file, so anyone who ever held that file could quote
 * its preshared key; only a phone that made its own key can be said to be the sole other holder.
 */
public record PeerProof(String publicKey, String presharedKey) {

    public static PeerProof of(String publicKey, String presharedKey) {
        if (publicKey == null || publicKey.isBlank() || presharedKey == null || presharedKey.isBlank()) {
            throw new IllegalArgumentException("Leaving requires both the device's public key and its "
                + "preshared key");
        }
        return new PeerProof(publicKey.trim(), presharedKey.trim());
    }

    /** Whether this proof is the config {@code peer} holds — the whole authorisation for leaving. */
    public boolean proves(PeerConfiguration peer) {
        if (peer == null || !peer.deviceHeldKey() || !publicKey.equals(peer.publicKey())) {
            return false;
        }
        String stored = WireGuardPeerConfig.readDirective(peer.configContent(), "PresharedKey");
        if (stored == null || stored.isBlank()) {
            return false;
        }
        // Constant-time: the preshared key is the secret being checked, so a wrong one must take the
        // same time to reject however much of it is right.
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
            presharedKey.getBytes(StandardCharsets.UTF_8));
    }

    /** The peer in {@code peers} this proof proves, if any. */
    public Optional<PeerConfiguration> whichPeer(List<PeerConfiguration> peers) {
        if (peers == null) {
            return Optional.empty();
        }
        return peers.stream().filter(this::proves).findFirst();
    }
}
