package net.vaier.application;

import net.vaier.domain.MachineId;

public interface DeleteBackupJobUseCase {

    /**
     * Stop backing up the machine {@code machineId} — forget its job; a no-op when it has none. The
     * repository is untouched: the archives already made stay on the backup server.
     */
    void deleteBackupJob(MachineId machineId);
}
