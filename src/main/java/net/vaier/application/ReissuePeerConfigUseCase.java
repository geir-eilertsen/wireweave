package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;

public interface ReissuePeerConfigUseCase {

    /**
     * Re-renders a peer's installable config from the current generation logic, preserving its
     * keypair and preshared key, persists it, and re-opens the one-shot retrieval budget so the
     * fresh config can be delivered once more. See {@code Reissue} in UBIQUITOUS_LANGUAGE.md.
     * Throws {@code PeerNotFoundException} when the peer is unknown, and {@code ConflictException}
     * for a peer with a {@code Device-held key} — there is no installable config to re-render, because
     * the private half was minted on the device and has never existed here.
     */
    ReissuedPeerUco reissuePeerConfig(String peerId);

    /**
     * The outcome of a reissue. Mirrors {@code CreatedPeerUco} minus the private key (which is
     * already inside {@code clientConfigFile}), so the web layer can render the same success modal.
     * Never describes a {@code Device-held key}: such a peer is refused before anything is rendered.
     */
    record ReissuedPeerUco(
        String id,
        MachineId machineId,
        String name,
        String ipAddress,
        String publicKey,
        String clientConfigFile,
        MachineType peerType
    ) {}
}
