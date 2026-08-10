package net.fjordomatic.domain;

/**
 * The SSH login an operator supplies while a machine is still only a discovered candidate: a username,
 * an {@link AuthMethod}, the secret material, and an optional key passphrase — everything a
 * {@link HostCredential} holds except the machine identity, which does not exist yet.
 *
 * <p>It is the shape both halves of "attach a credential during adoption" work from: a pre-registration
 * {@link #targetAt test target} (which pins nothing — nothing is trusted for a machine never connected
 * to) and, once the machine is registered, the {@link #forMachine vault credential} keyed to its new
 * name. Keeping both derivations here means "a tested-but-unregistered credential pins no host key" and
 * "an adopted credential is unmanaged and keyed to the machine's identity" are decided in one place.
 */
public record SshCredentialDraft(String username, AuthMethod authMethod, String secret, String passphrase) {

    /**
     * What Fjord accepts as private-key material — an OpenSSH ({@code BEGIN OPENSSH PRIVATE KEY}) or
     * legacy PEM ({@code BEGIN RSA/EC/DSA PRIVATE KEY}, {@code BEGIN PRIVATE KEY}) block.
     */
    private static final String PRIVATE_KEY_MARKER = "PRIVATE KEY-----";

    /**
     * A draft is refused outright when it claims {@link AuthMethod#PRIVATE_KEY} but carries something that
     * is structurally not a private key — most often the {@code .pub} half pasted by mistake, or a PuTTY
     * {@code .ppk}. Such a credential saves perfectly happily (the vault sees only a string) and then fails
     * at <em>every</em> connect — the terminal, remote commands, Explorer listings, backup runs — with
     * nothing on screen pointing at the key material. Deciding it here means the operator hears about it
     * while they are still looking at the form (#350).
     *
     * <p>The check is deliberately structural, not cryptographic: whether the bytes are a <em>valid</em>
     * key is the SSH adapter's business, and the domain does not parse key material.
     */
    public SshCredentialDraft {
        if (authMethod == AuthMethod.PRIVATE_KEY && secret != null && !secret.contains(PRIVATE_KEY_MARKER)) {
            throw new IllegalArgumentException("secret must be an SSH private key — expected a "
                + "\"-----BEGIN ... PRIVATE KEY-----\" block (ed25519, ECDSA or RSA), not a .pub public key "
                + "or a PuTTY .ppk");
        }
    }

    /**
     * The {@link SshTarget} to test this credential against {@code address}:{@code port}, with no pinned
     * fingerprint — a pre-registration test trusts on first use and records nothing.
     */
    public SshTarget targetAt(String address, int port) {
        return new SshTarget(address, port, username, authMethod, secret, passphrase, null);
    }

    /**
     * This draft as the vault {@link HostCredential} for the machine identified by {@code machineId}. Always unmanaged
     * ({@code managed=false}) — Fjord did not generate this keypair; the operator supplied it.
     */
    public HostCredential forMachine(MachineId machineId) {
        return new HostCredential(machineId, username, authMethod, secret, passphrase, false);
    }
}
