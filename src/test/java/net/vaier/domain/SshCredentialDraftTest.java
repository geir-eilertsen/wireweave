package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SshCredentialDraftTest {

    private static net.vaier.domain.MachineId mid(String name) {
        return net.vaier.domain.TestMachineIds.of(name);
    }

    @Test
    void targetAt_buildsATargetForTheAddressAndPort_withNoPinnedFingerprint() {
        // Pre-registration: the machine has never been connected to, so nothing is pinned yet.
        SshCredentialDraft draft = new SshCredentialDraft("root", AuthMethod.PASSWORD, "pw", null);

        SshTarget target = draft.targetAt("192.168.3.50", 2222);

        assertThat(target.host()).isEqualTo("192.168.3.50");
        assertThat(target.port()).isEqualTo(2222);
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(target.secret()).isEqualTo("pw");
        assertThat(target.pinnedFingerprint()).isNull();
    }

    @Test
    void forMachine_keysAnUnmanagedCredentialToTheMachineName() {
        SshCredentialDraft draft =
            new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY, OPENSSH_PRIVATE_KEY, "keypass");

        HostCredential credential = draft.forMachine(mid("nas"));

        assertThat(credential).isEqualTo(new HostCredential(mid("nas"), "admin", AuthMethod.PRIVATE_KEY,
            OPENSSH_PRIVATE_KEY, "keypass", false));
    }

    // #350 — a credential whose key material can never authenticate must be refused while the operator is
    // still looking at the form, not silently stored to fail later at every single connect.

    @Test
    void rejectsAPublicKeyPastedWherePrivateKeyMaterialBelongs() {
        assertThatThrownBy(() -> new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY,
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIM0Jd geir@example.test", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PRIVATE KEY")
            .hasMessageContaining(".pub");
    }

    @Test
    void rejectsAPuttyPpkWherePrivateKeyMaterialBelongs() {
        assertThatThrownBy(() -> new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY,
            "PuTTY-User-Key-File-3: ssh-ed25519\nEncryption: none\nComment: nas\n", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(".ppk");
    }

    @Test
    void acceptsAnOpenSshPrivateKeyBlock() {
        SshCredentialDraft draft =
            new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY, OPENSSH_PRIVATE_KEY, null);

        assertThat(draft.secret()).isEqualTo(OPENSSH_PRIVATE_KEY);
    }

    @Test
    void acceptsALegacyPemPrivateKeyBlock() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----\n";

        assertThat(new SshCredentialDraft("admin", AuthMethod.PRIVATE_KEY, pem, null).secret()).isEqualTo(pem);
    }

    @Test
    void aPasswordSecretIsNotHeldToTheKeyFormat() {
        SshCredentialDraft draft = new SshCredentialDraft("root", AuthMethod.PASSWORD, "hunter2", null);

        assertThat(draft.secret()).isEqualTo("hunter2");
    }

    private static final String OPENSSH_PRIVATE_KEY =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEA\n-----END OPENSSH PRIVATE KEY-----\n";
}
