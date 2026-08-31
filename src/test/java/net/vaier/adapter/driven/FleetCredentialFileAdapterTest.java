package net.vaier.adapter.driven;

import net.vaier.domain.FleetCredential;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FleetCredentialFileAdapterTest {

    @TempDir
    Path tempDir;

    private FleetCredentialFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FleetCredentialFileAdapter(tempDir.toString(), new SecretCipher(tempDir.toString()));
    }

    private static FleetCredential credential() {
        return FleetCredential.of("claude-oauth", "~/.claude/.credentials.json", "0600",
            "{\"token\":\"totally-secret-value\"}");
    }

    @Test
    void getByName_emptyWhenNothingStored() {
        assertThat(adapter.getByName("claude-oauth")).isEmpty();
    }

    @Test
    void getAll_emptyWhenNothingStored() {
        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void save_thenGetByName_roundTripsTheWholeCredential() {
        adapter.save(credential());

        assertThat(adapter.getByName("claude-oauth")).contains(credential());
    }

    @Test
    void save_roundTripsTheDistributedFlag_soARedeployDoesNotStopTheReconcile() {
        adapter.save(credential().markDistributed());

        assertThat(adapter.getByName("claude-oauth")).map(FleetCredential::distributed).contains(true);
    }

    @Test
    void save_replacesAnExistingCredentialOfTheSameName() {
        adapter.save(credential());
        adapter.save(FleetCredential.of("claude-oauth", "/etc/vaier/token", "0640", "second"));

        assertThat(adapter.getAll()).hasSize(1);
        assertThat(adapter.getByName("claude-oauth")).map(FleetCredential::content).contains("second");
    }

    @Test
    void save_sealsTheContentAtRest_soTheFileHasNoPlaintextSecret() throws Exception {
        adapter.save(credential());

        String onDisk = Files.readString(tempDir.resolve("fleet-credentials.yml"));
        assertThat(onDisk).doesNotContain("totally-secret-value");
        assertThat(onDisk).contains("enc:v1:");
        // Everything that is not the secret stays legible on disk.
        assertThat(onDisk).contains("claude-oauth").contains("~/.claude/.credentials.json");
    }

    @Test
    void save_locksTheFileDownToTheOwner() throws Exception {
        adapter.save(credential());

        assertThat(Files.getPosixFilePermissions(tempDir.resolve("fleet-credentials.yml")))
            .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void deleteByName_removesOnlyThatCredential() {
        adapter.save(credential());
        adapter.save(FleetCredential.of("other", "/etc/other", "0600", "x"));

        adapter.deleteByName("claude-oauth");

        assertThat(adapter.getByName("claude-oauth")).isEmpty();
        assertThat(adapter.getByName("other")).isPresent();
    }

    @Test
    void deleteByName_isANoOpForAnUnknownName() {
        adapter.deleteByName("never-existed");

        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void getAll_skipsAMalformedEntryRatherThanLosingEveryOtherCredential() throws Exception {
        adapter.save(credential());
        String onDisk = Files.readString(tempDir.resolve("fleet-credentials.yml"));
        Files.writeString(tempDir.resolve("fleet-credentials.yml"),
            onDisk + "- name: 'not a safe name'\n  targetPath: '/etc/x'\n  mode: '0600'\n"
                + "  content: 'x'\n  distributed: false\n");

        assertThat(adapter.getAll()).hasSize(1);
        assertThat(adapter.getByName("claude-oauth")).isPresent();
    }
}
