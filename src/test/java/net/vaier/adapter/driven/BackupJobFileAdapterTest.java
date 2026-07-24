package net.vaier.adapter.driven;

import java.nio.file.Files;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.BackupJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupJobFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupJobFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupJobFileAdapter(tempDir.toString());
    }

    @Test
    void getByName_emptyWhenNothingStored() {
        assertThat(adapter.getByName("colina-home")).isEmpty();
    }

    @Test
    void roundTripsIncludingListFields() {
        BackupJob job = new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home/geir", "/etc"), List.of("*.tmp", "**/node_modules"),
            7, 4, 6, "zstd,6", true, false);

        adapter.save(job);

        // Fresh adapter reload proves it survives on disk with its list fields intact.
        BackupJobFileAdapter fresh = new BackupJobFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("colina-home")).contains(job);
        assertThat(fresh.getByName("colina-home").orElseThrow().sourcePaths())
            .containsExactly("/home/geir", "/etc");
        assertThat(fresh.getByName("colina-home").orElseThrow().excludes())
            .containsExactly("*.tmp", "**/node_modules");
    }

    // --- Back up as root ---

    @Test
    void roundTripsBackupAsRoot() {
        BackupJob asRoot = new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, true);

        adapter.save(asRoot);

        BackupJobFileAdapter fresh = new BackupJobFileAdapter(tempDir.toString());
        assertThat(fresh.getByName("colina-home").orElseThrow().backupAsRoot()).isTrue();
    }

    /**
     * The path EVERY job file on every host takes on first load after this change: no {@code backupAsRoot} key
     * at all. It must load as {@code false} — a job never escalates itself to root because a new field appeared.
     */
    @Test
    void loadsAnExistingJobFileWithNoBackupAsRootKeyAsFalse() throws Exception {
        Files.writeString(tempDir.resolve("backup-jobs.yml"), """
            jobs:
            - name: colina-home
              machineId: %s
              repositoryName: nas-borg
              sourcePaths:
              - /home/geir
              excludes: []
              keepDaily: 7
              keepWeekly: 4
              keepMonthly: 6
              compression: zstd,6
              enabled: true
            """.formatted(TestMachineIds.of("Colina 27")));

        BackupJob loaded = adapter.getByName("colina-home").orElseThrow();

        assertThat(loaded.backupAsRoot()).isFalse();
        // And the rest of the job still loads exactly as before.
        assertThat(loaded.machineId()).isEqualTo(TestMachineIds.of("Colina 27"));
        assertThat(loaded.sourcePaths()).containsExactly("/home/geir");
        assertThat(loaded.enabled()).isTrue();
    }

    @Test
    void roundTripsJobWithEmptyExcludes() {
        BackupJob job = new BackupJob("roon", TestMachineIds.of("Roon server"), "nas-borg",
            List.of("/data"), List.of(), 7, 0, 0, "zstd,6", false, false);

        adapter.save(job);

        assertThat(adapter.getByName("roon")).contains(job);
    }

    @Test
    void getByMachine_returnsEveryJobForThatMachine() {
        adapter.save(new BackupJob("a", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("b", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("c", TestMachineIds.of("Apalveien 5"), "nas-borg", List.of("/c"), List.of(), 1, 0, 0, "zstd,6", true, false));

        assertThat(adapter.getByMachine(TestMachineIds.of("Colina 27"))).extracting(BackupJob::name)
            .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/old"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false, false));

        assertThat(adapter.getAll()).containsExactly(
            new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false, false));
    }

    @Test
    void deleteByName_removesEntry() {
        adapter.save(new BackupJob("a", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("b", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true, false));

        adapter.deleteByName("a");

        assertThat(adapter.getAll()).extracting(BackupJob::name).containsExactly("b");
    }

    /**
     * A job whose stored machine id is missing or unreadable is skipped <b>loudly</b>, and the rest of the
     * file still loads. Loudly, because a job that quietly fails to load is a machine that silently stops
     * being backed up, and nothing else in Vaier would ever say so — which is exactly what name-keying cost
     * here, in two dead jobs that ran nightly against machines in no registry.
     */
    @Test
    void getAll_skipsAJobWithAnUnreadableMachineId_andKeepsTheRest() throws Exception {
        Files.writeString(tempDir.resolve("backup-jobs.yml"), """
            jobs:
            - name: orphan
              machineId: Colina 27
              repositoryName: nas-borg
              sourcePaths:
              - /home/geir
              keepDaily: 7
              keepWeekly: 4
              keepMonthly: 6
              enabled: true
            - name: colina-home
              machineId: %s
              repositoryName: nas-borg
              sourcePaths:
              - /home/geir
              keepDaily: 7
              keepWeekly: 4
              keepMonthly: 6
              enabled: true
            """.formatted(TestMachineIds.of("Colina 27")));

        assertThat(adapter.getAll()).extracting(BackupJob::name).containsExactly("colina-home");
    }

    /** A job with no machine id at all is the same failure, not a job for "some machine". */
    @Test
    void getAll_skipsAJobWithNoMachineIdAtAll() throws Exception {
        Files.writeString(tempDir.resolve("backup-jobs.yml"), """
            jobs:
            - name: orphan
              repositoryName: nas-borg
              sourcePaths:
              - /home/geir
              keepDaily: 7
              keepWeekly: 4
              keepMonthly: 6
              enabled: true
            """);

        assertThat(adapter.getAll()).isEmpty();
    }
}
