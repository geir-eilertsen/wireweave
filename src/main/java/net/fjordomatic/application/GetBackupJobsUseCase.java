package net.fjordomatic.application;

import net.fjordomatic.domain.BackupJob;

import java.util.List;

public interface GetBackupJobsUseCase {

    /** Every configured fleet-backup job. */
    List<BackupJob> getBackupJobs();
}
