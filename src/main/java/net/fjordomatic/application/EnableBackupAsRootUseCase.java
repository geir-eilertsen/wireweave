package net.fjordomatic.application;

import net.fjordomatic.domain.BackupAsRootOutcome;
import net.fjordomatic.domain.MachineId;

/**
 * Say yes to <b>Back up as root</b> for one machine, as a single operator action (#334).
 *
 * <p>It exists because the question used to take two: the toggle on the job, and — separately, and
 * undiscoverably — the sudoers grant that makes the toggle do anything. An operator who flipped the toggle
 * on a machine without the grant got a job whose every run died before borg started. This is the one call
 * that does both, and reports honestly when the grant is not (yet) there.
 */
public interface EnableBackupAsRootUseCase {

    /**
     * Make {@code machineId}'s backup job read every file, whoever owns them: install the grant where it is
     * missing and turn the job's flag on where the machine actually grants it. The
     * {@link BackupAsRootOutcome} says which of those happened — it is never a bare "done".
     *
     * @throws net.fjordomatic.domain.NotFoundException when nothing on the machine is backed up, so there is no
     *                                            job to change
     */
    BackupAsRootOutcome enableBackupAsRoot(MachineId machineId);
}
