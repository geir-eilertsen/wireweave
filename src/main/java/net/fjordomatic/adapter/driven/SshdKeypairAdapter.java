package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.port.ForGeneratingSshKeypairs;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collection;

/**
 * {@link ForGeneratingSshKeypairs} backed by Apache MINA sshd — the same library that later has to read
 * the key back at connect time, which is exactly why it also writes it: a key minted and serialised by
 * the parser's own writer cannot be a format that parser refuses.
 */
@Component
public class SshdKeypairAdapter implements ForGeneratingSshKeypairs {

    /** ed25519 keys have one size; sshd still wants it named. */
    private static final int ED25519_KEY_SIZE = 256;

    @Override
    public String generatePrivateKey(String comment) {
        try {
            KeyPair keyPair = KeyUtils.generateKeyPair(KeyPairProvider.SSH_ED25519, ED25519_KEY_SIZE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // A null encryption context writes the key unencrypted — deliberate. The vault encrypts it at
            // rest, and a passphrase Fjord would have to store next to the key it protects adds nothing.
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, comment, null, out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate an ed25519 keypair: "
                + SshConnector.rootMessage(e), e);
        }
    }

    @Override
    public String publicKeyFor(String privateKey, String passphrase, String comment) {
        try {
            FilePasswordProvider passwordProvider = (passphrase == null || passphrase.isBlank())
                ? null : FilePasswordProvider.of(passphrase);
            Collection<KeyPair> keys = SecurityUtils.getKeyPairResourceParser()
                .loadKeyPairs(null, NamedResource.ofName("credential"), passwordProvider, privateKey);
            if (keys == null || keys.isEmpty()) {
                throw new IllegalArgumentException("no private key found in the stored credential");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OpenSSHKeyPairResourceWriter.INSTANCE.writePublicKey(
                keys.iterator().next().getPublic(), comment, out);
            return out.toString(StandardCharsets.UTF_8).strip();
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the public half of the stored private key: "
                + SshConnector.rootMessage(e), e);
        }
    }
}
