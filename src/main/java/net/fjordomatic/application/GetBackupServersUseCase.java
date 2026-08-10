package net.fjordomatic.application;

import net.fjordomatic.domain.BackupServer;

import java.util.List;

public interface GetBackupServersUseCase {

    /** Every configured fleet-backup server. */
    List<BackupServer> getBackupServers();
}
