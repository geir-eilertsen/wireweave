package net.vaier.domain.port;

import net.vaier.domain.BackupJob;
import net.vaier.domain.MachineId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for persisting the fleet-backup {@link BackupJob} definitions, keyed by the
 * {@link MachineId} of the machine each one backs up.
 *
 * <p>By machine and not by job name: a job's name is a label an operator reads, and machine names need not
 * be unique (§6.22), so two machines called "NAS" produced two jobs called "NAS" — of which this file kept
 * one. The machine that lost the race went on looking backed up while its nightly run simply never
 * happened again. A machine has one job; that is what makes the machine the key.
 */
public interface ForPersistingBackupJobs {

    /** Every stored backup job. */
    List<BackupJob> getAll();

    /** The job named {@code name}, or empty when none is stored. A label lookup, for reading only. */
    Optional<BackupJob> getByName(String name);

    /** Every job that backs up the machine {@code machineId}. */
    List<BackupJob> getByMachine(MachineId machineId);

    /** Persist {@code job}, replacing the machine's existing job if it has one. */
    void save(BackupJob job);

    /** Forget the job backing up this machine; a no-op when it has none. */
    void deleteByMachine(MachineId machineId);
}
