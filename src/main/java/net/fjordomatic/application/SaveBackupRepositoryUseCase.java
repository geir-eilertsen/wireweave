package net.fjordomatic.application;

import net.fjordomatic.domain.BackupRepository;

public interface SaveBackupRepositoryUseCase {

    /** Store (or replace) a fleet-backup repository definition. */
    void saveBackupRepository(BackupRepository repository);
}
