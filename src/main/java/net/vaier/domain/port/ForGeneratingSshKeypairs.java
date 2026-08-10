package net.vaier.domain.port;

/**
 * Driven port for the SSH key-material Vaier mints itself (#309): generating a keypair for a machine it
 * cannot reach yet, and reading the public half back out of a stored private key.
 *
 * <p>Both operations cross the same boundary — a JCA/sshd concern the domain must not know about — so
 * they share one port rather than earning two. Generation happens in Vaier's own process, unlike
 * {@code BorgCommand.ensureClientKeyPair}, which shells {@code ssh-keygen} on a host Vaier can already
 * log in to; the whole point of a managed keypair is that no such login exists yet.
 *
 * <p>The public half is <em>derived</em> rather than stored: a private key already determines it, so
 * there is nothing to keep in step and nothing to migrate for credentials written before this existed.
 */
public interface ForGeneratingSshKeypairs {

    /**
     * A freshly minted ed25519 keypair as OpenSSH new-format private-key text, unencrypted (no
     * passphrase — the vault already encrypts it at rest, and a passphrase Vaier holds beside the key it
     * protects secures nothing). {@code comment} is the trailing label {@code ssh-keygen} would write.
     */
    String generatePrivateKey(String comment);

    /**
     * The {@code ssh-ed25519 AAAA... comment} public-key line for {@code privateKey} — the one line an
     * operator pastes into a host's {@code authorized_keys}. Works for any key Vaier can read, so a
     * pasted key can report its public half too, not only a generated one.
     *
     * <p>The {@code comment} is supplied rather than recovered from the key: a private key's own comment
     * does not survive being parsed into a key pair, and what an operator needs on that line is a label
     * saying whose key it is when they later audit {@code authorized_keys}.
     *
     * @param passphrase the key's passphrase, or null when it has none
     */
    String publicKeyFor(String privateKey, String passphrase, String comment);
}
