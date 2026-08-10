package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.SshTarget;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #309 — the keypairs Vaier mints for itself. The test that matters most is the round trip: a generated
 * key is worthless unless {@link SshConnector}, the one place every SSH path reads a key, can parse it.
 */
class SshdKeypairAdapterTest {

    private final SshdKeypairAdapter adapter = new SshdKeypairAdapter();

    @Test
    void generatesAnUnencryptedOpenSshEd25519PrivateKey() {
        String privateKey = adapter.generatePrivateKey("vaier@nas");

        assertThat(privateKey)
            .startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")
            .contains("-----END OPENSSH PRIVATE KEY-----");
    }

    @Test
    void theGeneratedKeyParsesThroughTheSamePathEverySshConnectUses() {
        String privateKey = adapter.generatePrivateKey("vaier@nas");

        Collection<KeyPair> keys = SshConnector.loadKeyPairs(
            new SshTarget("nas.example.test", 22, "admin", AuthMethod.PRIVATE_KEY, privateKey, null, null));

        assertThat(keys).hasSize(1);
        assertThat(KeyUtils.getKeyType(keys.iterator().next())).isEqualTo(KeyPairProvider.SSH_ED25519);
    }

    @Test
    void generatesADifferentKeyEveryTime() {
        assertThat(adapter.generatePrivateKey("vaier@nas"))
            .isNotEqualTo(adapter.generatePrivateKey("vaier@nas"));
    }

    @Test
    void publicKeyForAGeneratedKeyIsAnAuthorizedKeysLine() {
        String privateKey = adapter.generatePrivateKey("vaier@nas");

        String publicKey = adapter.publicKeyFor(privateKey, null, "vaier");

        assertThat(publicKey).startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5").endsWith(" vaier");
        assertThat(publicKey.lines()).hasSize(1);
    }

    @Test
    void publicKeyIsStableForTheSamePrivateKey() {
        String privateKey = adapter.generatePrivateKey("vaier@nas");

        assertThat(adapter.publicKeyFor(privateKey, null, "vaier")).isEqualTo(adapter.publicKeyFor(privateKey, null, "vaier"));
    }

    @Test
    void publicKeyForAPastedKeyIsDerivedToo() throws Exception {
        // Deriving rather than storing means a key the operator pasted can report its public half as
        // readily as one Vaier minted — there is no stored field that only generated keys would have.
        String pasted = Files.readString(
            Path.of("src/test/resources/ssh-keys/throwaway-ed25519.pem"), StandardCharsets.UTF_8);
        String expected = Files.readString(
            Path.of("src/test/resources/ssh-keys/throwaway-ed25519.pem.pub"), StandardCharsets.UTF_8).strip();

        // ssh-keygen's own .pub line, minus the comment, which is not part of the key.
        assertThat(adapter.publicKeyFor(pasted, null, "vaier"))
            .startsWith(expected.substring(0, expected.lastIndexOf(' ')));
    }

    @Test
    void unreadableKeyMaterialIsRefused() {
        assertThatThrownBy(() -> adapter.publicKeyFor("not a key", null, "vaier"))
            .isInstanceOf(RuntimeException.class);
    }
}
