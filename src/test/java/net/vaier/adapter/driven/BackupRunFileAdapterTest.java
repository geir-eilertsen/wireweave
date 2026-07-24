package net.vaier.adapter.driven;

import java.nio.file.Files;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRunFileAdapterTest {

    @TempDir
    Path tempDir;

    private BackupRunFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BackupRunFileAdapter(tempDir.toString());
    }

    private BackupRun run(String runId, String jobName, BackupRunStatus status, Integer exitCode) {
        return new BackupRun(runId, jobName, "nas-borg", TestMachineIds.of("Colina 27"), status,
            Instant.parse("2026-07-08T02:00:00Z"),
            status.isTerminal() ? Instant.parse("2026-07-08T02:05:00Z") : null,
            exitCode, "{hostname}-{now:%Y-%m-%dT%H:%M:%S}", "Backup completed");
    }

    @Test
    void latestForJob_emptyWhenNothingRecorded() {
        assertThat(adapter.latestForJob("colina-home")).isEmpty();
    }

    @Test
    void persistsLatestRunPerJobAcrossReload() {
        adapter.record(run("run-1", "colina-home", BackupRunStatus.RUNNING, null));
        adapter.record(run("run-2", "colina-home", BackupRunStatus.SUCCESS, 0));
        adapter.record(run("run-3", "roon", BackupRunStatus.FAILED, 2));

        // A fresh adapter reads the persisted file — the latest run per job survives a restart.
        BackupRunFileAdapter fresh = new BackupRunFileAdapter(tempDir.toString());

        assertThat(fresh.latestForJob("colina-home"))
            .contains(run("run-2", "colina-home", BackupRunStatus.SUCCESS, 0));
        assertThat(fresh.latestForJob("roon"))
            .contains(run("run-3", "roon", BackupRunStatus.FAILED, 2));
        // Only the latest run per job is retained (one per job), not the full history.
        assertThat(fresh.getAll()).hasSize(2);
    }

    @Test
    void warningRunSurvivesSaveLoadRoundTrip() {
        // A borg-exit-1 WARNING run persists like any other terminal status via valueOf/name.
        adapter.record(run("run-w", "colina-home", BackupRunStatus.WARNING, 1));

        BackupRunFileAdapter fresh = new BackupRunFileAdapter(tempDir.toString());

        assertThat(fresh.latestForJob("colina-home"))
            .contains(run("run-w", "colina-home", BackupRunStatus.WARNING, 1));
    }

    @Test
    void getAll_emptyWhenFileMissing() {
        assertThat(adapter.getAll()).isEmpty();
    }

    /**
     * A run whose stored machine id is unreadable is skipped <b>loudly</b>. A run that cannot name its
     * machine can never be polled to a conclusion, so an in-flight {@code RUNNING} run would sit unresolved
     * forever while the job read as having never run — a backup tool's worst kind of quiet.
     */
    @Test
    void getAll_skipsARunWithAnUnreadableMachineId() throws Exception {
        Files.writeString(tempDir.resolve("backup-runs.yml"), """
            runs:
            - runId: run-1
              jobName: colina-home
              repositoryName: nas-borg
              machineId: Colina 27
              status: RUNNING
              startedAt: 2026-07-08T02:00:00Z
            """);

        assertThat(adapter.getAll()).isEmpty();
    }
}
