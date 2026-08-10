package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForGeneratingSshKeypairs;

/**
 * The single host credential Fjord holds for a machine so it can open an SSH session to it: the
 * login {@code username}, the {@link AuthMethod}, the {@code secret} (the password, or the private-key
 * PEM), and an optional key {@code passphrase}. {@code managed} marks a credential whose keypair Fjord
 * itself generated — a managed keypair, minted by {@link #generatedFor} and by nothing else.
 *
 * <p>The secret material lives here in the clear — encryption at rest is a persistence concern the
 * adapter applies on the way to disk, so the domain stays free of it. Whether a value is safe to
 * expose is a domain decision: {@link #toView()} produces the redacted {@link HostCredentialView} that
 * is the only shape allowed to leave the process.
 */
public record HostCredential(MachineId machineId, String username, AuthMethod authMethod,
                             String secret, String passphrase, boolean managed) {

    public HostCredential {
        if (machineId == null) {
            throw new IllegalArgumentException("machineId must not be null");
        }
        requireNonBlank(username, "username");
        requireNonBlank(secret, "secret");
        if (authMethod == null) {
            throw new IllegalArgumentException("authMethod must not be null");
        }
    }

    /**
     * The comment written on the public-key line Fjord hands the operator. It ends up in the host's
     * {@code authorized_keys}, which is the one place a human later asks "which of these lines is
     * Fjord's?" — so it says so.
     */
    public static final String PUBLIC_KEY_COMMENT = "vaier";

    /**
     * A managed keypair for {@code machineId}: Fjord mints the keypair itself and keeps the private half.
     * This is the only place {@code managed} becomes true — a credential an operator pasted is never
     * managed, however it was pasted — which is what makes the flag mean something the whole system can
     * rely on rather than a value a caller happened to pass.
     *
     * <p>The generator port is handed in and called here, so "what a managed keypair is" — key auth, no
     * passphrase, managed — stays one domain decision rather than something a service assembles. Which
     * algorithm the key uses is not among them: that is the port's adapter's choice, pinned by its contract.
     */
    public static HostCredential generatedFor(MachineId machineId, String username,
                                              ForGeneratingSshKeypairs keypairs) {
        return new HostCredential(machineId, username, AuthMethod.PRIVATE_KEY,
            keypairs.generatePrivateKey(PUBLIC_KEY_COMMENT), null, true);
    }

    /**
     * The public half of this credential's key — the {@code authorized_keys} line — or null when there is
     * no key to derive one from (a password credential). Derived on demand rather than stored: a private
     * key already determines its public half, so there is no second copy to fall out of step with it, and
     * nothing to migrate for credentials written before managed keypairs existed.
     *
     * <p>A public key is not secret, which is why this is allowed to return it while {@link #toView()}
     * redacts everything else.
     */
    public String publicKey(ForGeneratingSshKeypairs keypairs) {
        if (authMethod != AuthMethod.PRIVATE_KEY) {
            return null;
        }
        return keypairs.publicKeyFor(secret, passphrase, PUBLIC_KEY_COMMENT);
    }

    /** The redacted view of this credential — carries no secret or passphrase bytes. */
    public HostCredentialView toView() {
        return new HostCredentialView(machineId, username, authMethod,
            secret != null && !secret.isBlank(), managed);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
