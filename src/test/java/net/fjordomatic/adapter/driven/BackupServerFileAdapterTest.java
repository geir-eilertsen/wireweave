package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.BackupServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BackupServerFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupServerFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupServerFileAdapter(tempDir.toString());
    }

    private BackupServer nas() {
        return new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
    }

    @Test
    void getAll_emptyWhenFileMissing() {
        assertThat(adapter.getAll()).isEmpty();
    }

    @Test
    void getByName_emptyWhenNothingStored() {
        assertThat(adapter.getByName("nas-borg")).isEmpty();
    }

    @Test
    void roundTripsThroughAFreshAdapter() {
        BackupServer server = nas();
        adapter.save(server);

        BackupServerFileAdapter fresh = new BackupServerFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("nas-borg")).contains(server);
        assertThat(fresh.getAll()).containsExactly(server);
    }

    @Test
    void writesUnderTheServersRootKey() throws Exception {
        adapter.save(nas());
        String contents = Files.readString(tempDir.resolve("backup-servers.yml"));
        assertThat(contents)
            .contains("servers:")
            .contains("nas-borg")
            // The machine is stored by identity, so its NAME must not appear in the file at all — that is
            // the whole point: renaming it changes nothing here.
            .doesNotContain("machineName")
            .contains(TestMachineIds.of("NAS").value())
            .contains("192.168.3.3")
            .contains("home/borg/backups")
            .contains("/volume1/docker/borg");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/old", false));
        adapter.save(new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.9", 8022,
            "borg", "home/borg/backups", "/new", true));

        assertThat(adapter.getAll()).containsExactly(new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.9",
            8022, "borg", "home/borg/backups", "/new", true));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(nas());
        adapter.save(new BackupServer("other", TestMachineIds.of("Colina 27"), "192.168.1.4", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false));

        adapter.deleteByName("nas-borg");

        assertThat(adapter.getAll()).containsExactly(new BackupServer("other", TestMachineIds.of("Colina 27"), "192.168.1.4",
            8022, "borg", "home/borg/backups", "/volume1/docker/borg", false));
    }

    @Test
    void malformedEntryIsSkipped() throws Exception {
        // A hand-written file with one good and one malformed (missing host) entry.
        String yaml = """
            servers:
            - name: good
              machineId: %s
              host: 192.168.3.3
              sshPort: 8022
              borgUser: borg
              baseRepoPath: home/borg/backups
              serverDataPath: /volume1/docker/borg
              managed: true
            - name: broken
              machineId: %s
              sshPort: 8022
            """.formatted(TestMachineIds.of("NAS"), TestMachineIds.of("NAS"));
        Files.writeString(tempDir.resolve("backup-servers.yml"), yaml);

        assertThat(adapter.getAll()).extracting(BackupServer::name).containsExactly("good");
    }

    /**
     * A backup server whose stored machine id is unreadable is skipped <b>loudly</b>. Without its machine
     * Fjord cannot SSH to the borg server at all — no provisioning, no authorize, no health probe — and every
     * job on the fleet backs up through it.
     */
    @Test
    void getAll_skipsAServerWithAnUnreadableMachineId() throws Exception {
        Files.writeString(tempDir.resolve("backup-servers.yml"), """
            servers:
            - name: nas-borg
              machineId: NAS
              host: 192.168.3.3
              sshPort: 8022
            """);

        assertThat(adapter.getAll()).isEmpty();
    }
}
