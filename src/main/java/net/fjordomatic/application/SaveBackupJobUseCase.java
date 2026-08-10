package net.fjordomatic.application;

import net.fjordomatic.domain.BackupJob;

public interface SaveBackupJobUseCase {

    /** Store (or replace) a fleet-backup job. Rejected when it references an unknown repository. */
    void saveBackupJob(BackupJob job);
}
