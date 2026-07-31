package net.vaier.application;

import net.vaier.domain.MachineId;

/**
 * Mint a managed keypair for a machine: Vaier generates an ed25519 keypair, keeps the private half in
 * the credential vault, and hands back the public half for the operator to paste into that machine's
 * {@code authorized_keys}.
 *
 * <p>This is how a machine gets a login without anyone pasting a private key into a browser — the
 * private half is created inside Vaier and never leaves it.
 */
public interface GenerateManagedKeypairUseCase {

    /**
     * Generate and store a managed keypair for {@code machineId}, replacing any credential already held
     * for that machine, and return its public-key line.
     *
     * <p><strong>Destructive.</strong> Whatever login Vaier held for this machine is gone afterwards, and
     * the new key does not work until the operator installs it on the host — so a caller must confirm
     * with the operator before invoking this.
     */
    String generateManagedKeypair(MachineId machineId, String username);
}
