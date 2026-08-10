package net.vaier.domain;

import net.vaier.domain.port.ForGeneratingSshKeypairs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostCredentialTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @Test
    void constructs_aValidPasswordCredential() {
        HostCredential c = new HostCredential(mid("nas"), "admin", AuthMethod.PASSWORD, "s3cret", null, false);

        assertThat(c.machineId()).isEqualTo(mid("nas"));
        assertThat(c.username()).isEqualTo("admin");
        assertThat(c.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(c.secret()).isEqualTo("s3cret");
        assertThat(c.passphrase()).isNull();
        assertThat(c.managed()).isFalse();
    }

    @Test
    void rejects_missingMachineId() {
        assertThatThrownBy(() -> new HostCredential(null, "admin", AuthMethod.PASSWORD, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullUsername() {
        assertThatThrownBy(() -> new HostCredential(mid("nas"), null, AuthMethod.PASSWORD, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_blankSecret() {
        assertThatThrownBy(() -> new HostCredential(mid("nas"), "admin", AuthMethod.PASSWORD, "  ", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_nullAuthMethod() {
        assertThatThrownBy(() -> new HostCredential(mid("nas"), "admin", null, "s3cret", null, false))
            .isInstanceOf(IllegalArgumentException.class);
    }


    @Test
    void toView_redactsSecretAndPassphrase_butReportsSecretPresence() {
        HostCredential c = new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN KEY-----", "keypass", false);

        HostCredentialView view = c.toView();

        assertThat(view)
            .isEqualTo(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true, false));
    }

    // #309 — a managed keypair: one Vaier generated for itself, rather than one an operator pasted.

    @Test
    void generatedFor_mintsAManagedPrivateKeyCredential() {
        HostCredential c = HostCredential.generatedFor(mid("nas"), "admin", keypairs);

        assertThat(c.machineId()).isEqualTo(mid("nas"));
        assertThat(c.username()).isEqualTo("admin");
        assertThat(c.authMethod()).isEqualTo(AuthMethod.PRIVATE_KEY);
        assertThat(c.secret()).isEqualTo("GENERATED-PRIVATE-KEY");
        // No passphrase: the vault encrypts the key at rest, and one Vaier stored beside it protects nothing.
        assertThat(c.passphrase()).isNull();
        assertThat(c.managed()).isTrue();
    }

    @Test
    void generatedFor_isTheOnlyWayManagedBecomesTrue_soAPastedKeyStaysUnmanaged() {
        assertThat(new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY,
            "-----BEGIN OPENSSH PRIVATE KEY-----\nx\n-----END OPENSSH PRIVATE KEY-----\n", null)
            .forMachine(mid("nas")).managed()).isFalse();
    }

    @Test
    void toView_ofAManagedCredential_saysSoSoTheDialogCanTellThemApart() {
        assertThat(HostCredential.generatedFor(mid("nas"), "admin", keypairs).toView())
            .isEqualTo(new HostCredentialView(mid("nas"), "admin", AuthMethod.PRIVATE_KEY, true, true));
    }

    @Test
    void publicKey_derivesThePublicHalfFromTheStoredPrivateKey() {
        HostCredential c = HostCredential.generatedFor(mid("nas"), "admin", keypairs);

        assertThat(c.publicKey(keypairs)).isEqualTo("ssh-ed25519 PUB-OF(GENERATED-PRIVATE-KEY) vaier");
    }

    @Test
    void publicKey_ofAPasswordCredential_isEmpty_thereIsNoKeyToDerive() {
        HostCredential c = new HostCredential(mid("nas"), "admin", AuthMethod.PASSWORD, "s3cret", null, false);

        assertThat(c.publicKey(keypairs)).isNull();
    }

    /** A stand-in for the sshd-backed generator: the domain must not care which library mints the key. */
    private final ForGeneratingSshKeypairs keypairs = new ForGeneratingSshKeypairs() {
        @Override
        public String generatePrivateKey(String comment) {
            return "GENERATED-PRIVATE-KEY";
        }

        @Override
        public String publicKeyFor(String privateKey, String passphrase, String comment) {
            return "ssh-ed25519 PUB-OF(" + privateKey + ") " + comment;
        }
    };
}
