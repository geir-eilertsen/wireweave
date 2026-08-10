package net.fjordomatic.adapter.driven;

import java.nio.file.Files;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.BackupJob;
import net.fjordomatic.domain.MachineId;
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
    void getByMachine_returnsThatMachinesJob() {
        adapter.save(new BackupJob("a", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("c", TestMachineIds.of("Apalveien 5"), "nas-borg", List.of("/c"), List.of(), 1, 0, 0, "zstd,6", true, false));

        assertThat(adapter.getByMachine(TestMachineIds.of("Colina 27"))).extracting(BackupJob::name)
            .containsExactly("a");
    }

    @Test
    void save_aSecondJobForOneMachine_replacesTheFirst() {
        // Fjord makes exactly one job per machine, and the machine is now what keys the store — so writing
        // a second is writing the same one. It still READS a hand-edited file that holds two (see
        // BackupJobProtectedPathsAdapter, which folds them into one protection) rather than picking one.
        adapter.save(new BackupJob("a", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("b", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true, false));

        assertThat(adapter.getAll()).extracting(BackupJob::name).containsExactly("b");
    }

    @Test
    void save_sameName_replacesEntry() {
        adapter.save(new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/old"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false, false));

        assertThat(adapter.getAll()).containsExactly(
            new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/new"), List.of(), 3, 0, 0, "lz4", false, false));
    }

    @Test
    void deleteByMachine_removesEntry() {
        adapter.save(new BackupJob("a", TestMachineIds.of("Colina 27"), "nas-borg", List.of("/a"), List.of(), 1, 0, 0, "zstd,6", true, false));
        adapter.save(new BackupJob("b", TestMachineIds.of("Apalveien 5"), "nas-borg", List.of("/b"), List.of(), 1, 0, 0, "zstd,6", true, false));

        adapter.deleteByMachine(TestMachineIds.of("Colina 27"));

        assertThat(adapter.getAll()).extracting(BackupJob::name).containsExactly("b");
    }

    /**
     * A job whose stored machine id is missing or unreadable is skipped <b>loudly</b>, and the rest of the
     * file still loads. Loudly, because a job that quietly fails to load is a machine that silently stops
     * being backed up, and nothing else in Fjord would ever say so — which is exactly what name-keying cost
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

    // --- a job belongs to a machine, and that is what keys it (§6.22) --------------------------------

    @Test
    void save_sameMachine_replacesTheJob() {
        MachineId nas = MachineId.generate();
        adapter.save(job("NAS", nas, List.of("/home")));

        BackupJob renamed = job("The Norway box", nas, List.of("/home", "/etc"));
        adapter.save(renamed);

        assertThat(adapter.getAll()).containsExactly(renamed);
    }

    @Test
    void save_twoMachinesSharingAJobName_keepsBoth() {
        // A job's name is a label now — the machine is what identifies it. Keyed by name, the second of two
        // machines called "NAS" overwrote the first's job in this file, and the first silently stopped
        // being backed up: no error, and its nightly run simply never happened again.
        BackupJob here = job("NAS", MachineId.generate(), List.of("/home"));
        BackupJob there = job("NAS", MachineId.generate(), List.of("/home"));

        adapter.save(here);
        adapter.save(there);

        assertThat(adapter.getAll()).containsExactlyInAnyOrder(here, there);
    }

    @Test
    void deleteByMachine_removesOnlyThatMachinesJob() {
        BackupJob keep = job("NAS", MachineId.generate(), List.of("/home"));
        BackupJob go = job("NAS", MachineId.generate(), List.of("/home"));
        adapter.save(keep);
        adapter.save(go);

        adapter.deleteByMachine(go.machineId());

        assertThat(adapter.getAll()).containsExactly(keep);
    }

    @Test
    void deleteByMachine_unknownMachine_isNoOp() {
        adapter.save(job("NAS", MachineId.generate(), List.of("/home")));

        adapter.deleteByMachine(MachineId.generate());

        assertThat(adapter.getAll()).hasSize(1);
    }

    private static BackupJob job(String name, MachineId machineId, List<String> paths) {
        return new BackupJob(name, machineId, "nas-borg", paths, List.of(),
            7, 4, 6, "zstd,6", true, false);
    }
}
