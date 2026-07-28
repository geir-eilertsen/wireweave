package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;

public interface CreatePeerUseCase {

    CreatedPeerUco createPeer(String name);
    CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr);
    CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr, String lanAddress);
    CreatedPeerUco createPeer(String name, MachineType peerType, String lanCidr, String lanAddress,
                              String description);

    /**
     * The outcome of creating a peer.
     *
     * @param id   the peer's immutable identifier — the slug derived from the operator-typed
     *             {@code name} and deduplicated against existing peers. The WireGuard config
     *             directory name; never changes once assigned.
     * @param name the operator-typed display label, stored verbatim.
     */
    /**
     * @param machineId the identity minted for this peer and stamped into its config metadata — the one
     *                  moment a machine's identity is created rather than read. Returned so the caller
     *                  can stand on the new machine's coordinate without going back to the fleet and
     *                  matching on the name the operator typed.
     */
    record CreatedPeerUco(
        String id,
        MachineId machineId,
        String name,
        String ipAddress,
        String publicKey,
        String privateKey,
        String clientConfigFile,
        MachineType peerType
    ) {}
}
