package net.fjordomatic.application;

import net.fjordomatic.domain.MachineId;

import java.util.Optional;

/**
 * Read back the public half of the key credential held for a machine — the line to paste into the
 * host's {@code authorized_keys}. A public key is not secret, so unlike the private half it is allowed
 * out of the process.
 *
 * <p>Deliberately a separate read from {@link GetHostCredentialUseCase}: deriving the public key means
 * parsing the private one, which the frequent view reads (the machine list, the disk watcher, the backup
 * provisioner) have no use for and should not pay for — or fail on, for a key Fjord cannot read.
 */
public interface GetHostPublicKeyUseCase {

    /**
     * The public-key line for {@code machineId}'s credential; empty when no credential is held, when it
     * is a password credential, or when the stored key cannot be read.
     */
    Optional<String> getHostPublicKey(MachineId machineId);
}
