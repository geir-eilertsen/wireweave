package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;

/**
 * {@code Enrol} a device that minted its own WireGuard keypair (#359).
 *
 * <p>The mirror image of {@link CreatePeerUseCase}: there, Vaier generates the keypair and hands the
 * private half to the operator to install. Here the device — a phone running the Vaier app — generates
 * the pair itself, presents only the public half through the operator's signed-in browser, and receives
 * everything but the private key. Vaier never holds a secret it could leak, and there is nothing for the
 * operator to copy by hand.
 */
public interface EnrolDeviceUseCase {

    /**
     * Admits a device to the fleet under its own public key.
     *
     * @param name      what to call the device — the operator-facing label, exactly as for a peer
     * @param publicKey the {@code Device-held key}'s public half, base64 as WireGuard prints it
     * @throws IllegalArgumentException if the name is blank or the key is not a WireGuard key — thrown
     *                                  before anything is written or any peer reaches the server
     */
    EnrolledDeviceUco enrol(String name, String publicKey);

    /**
     * The outcome of an enrolment. Carries no private key and never can: the private half was minted on
     * the device and has never existed here.
     *
     * @param configFile the peer's installable WireGuard config, with no {@code PrivateKey} line — the
     *                   device fills that in from the key it already holds.
     */
    record EnrolledDeviceUco(
        String id,
        MachineId machineId,
        String name,
        String ipAddress,
        String publicKey,
        String configFile,
        MachineType peerType
    ) {}
}
