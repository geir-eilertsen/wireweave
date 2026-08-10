package net.fjordomatic.application;

import net.fjordomatic.domain.BackupRun;

/**
 * The {@code machineLabel} both calls take is the machine's display name. A {@link BackupRun} is keyed by
 * machine <em>identity</em>, so it cannot render a name itself; the caller has already resolved the machine
 * in order to reach it, and passes on what to call it in front of a person.
 */
public interface NotifyAdminsOfBackupFailureUseCase {

    /** Alert admins that a backup job's run failed — sent once when the job crosses from healthy to failing. */
    void notifyAdminsOfBackupFailure(BackupRun run, String machineLabel);

    /** Tell admins a previously failing backup job has succeeded again — the all-clear. */
    void notifyAdminsOfBackupRecovery(BackupRun run, String machineLabel);
}
