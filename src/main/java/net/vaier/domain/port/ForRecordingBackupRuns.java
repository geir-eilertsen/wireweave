package net.vaier.domain.port;

import net.vaier.domain.BackupRun;
import net.vaier.domain.MachineId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for recording {@link BackupRun}s and reading back a machine's last one.
 *
 * <p>Keyed by the machine, like the jobs it records runs for: a run's {@code jobName} is a label, and two
 * machines that shared one shared a run history — the second machine's run overwrote the first's, so the
 * first read as "backed up" on the strength of a run that happened somewhere else.
 */
public interface ForRecordingBackupRuns {

    /** Record {@code run} as the outcome (or in-flight state) of a backup execution. */
    void record(BackupRun run);

    /** The most recent run recorded for this machine, or empty when it has never run. */
    Optional<BackupRun> latestForMachine(MachineId machineId);

    /** Every recorded run. */
    List<BackupRun> getAll();
}
