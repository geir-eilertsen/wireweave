package net.fjordomatic.application;

import net.fjordomatic.domain.BackupRepository;

import java.util.List;

public interface GetBackupRepositoriesUseCase {

    /** Every configured fleet-backup repository. */
    List<BackupRepository> getBackupRepositories();
}
