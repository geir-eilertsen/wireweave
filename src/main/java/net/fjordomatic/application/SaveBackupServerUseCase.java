package net.fjordomatic.application;

import net.fjordomatic.domain.BackupServer;

public interface SaveBackupServerUseCase {

    /** Store (or replace) a fleet-backup server definition. */
    void saveBackupServer(BackupServer server);
}
