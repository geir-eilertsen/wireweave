package net.vaier.adapter.driven;

import net.vaier.domain.BackupJob;
import net.vaier.domain.Excludes;
import net.vaier.domain.MachineId;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.SourcePaths;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForReadingProtectedPaths;
import net.vaier.domain.port.ForResolvingMachineIds;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Reads what a machine backs up straight from the fleet-backup job store. Translation only: it gathers the
 * source paths <em>and</em> the excludes of every job that backs up the machine and hands both to the domain
 * ({@link SourcePaths#of}, {@link Excludes#of}, {@link ProtectedPaths#of}) — it makes no coverage decision of
 * its own. A machine with no job yields an empty {@link ProtectedPaths}, so the Explorer marks nothing on it.
 *
 * <p>A machine with no identity Vaier can resolve — a name that matches nothing — likewise yields an empty
 * {@link ProtectedPaths}: nothing is claimed to be backed up for a machine Vaier does not know.
 *
 * <p>The excludes are not optional detail. Reading only the source paths reported an excluded folder as
 * backed up, which is the one thing a backup tool must never say about data that is in no archive.
 */
@Component
public class BackupJobProtectedPathsAdapter implements ForReadingProtectedPaths {

    private final ForPersistingBackupJobs jobs;
    private final ForResolvingMachineIds machineIds;

    public BackupJobProtectedPathsAdapter(ForPersistingBackupJobs jobs, ForResolvingMachineIds machineIds) {
        this.jobs = jobs;
        this.machineIds = machineIds;
    }

    @Override
    public ProtectedPaths protectedPathsFor(String machineName) {
        // The Explorer still asks by name; the job store is keyed by identity. Crossing here — through the one
        // seam that does it — is why a machine that has been renamed still shows what it protects.
        Optional<MachineId> machineId = machineIds.idForName(machineName);
        if (machineId.isEmpty()) {
            return ProtectedPaths.of(SourcePaths.of(List.of()), Excludes.of(List.of()));
        }
        List<BackupJob> machineJobs = jobs.getByMachine(machineId.get());
        List<String> allPaths = machineJobs.stream()
            .map(BackupJob::sourcePaths)
            .flatMap(List::stream)
            .toList();
        List<String> allExcludes = machineJobs.stream()
            .map(BackupJob::excludes)
            .flatMap(List::stream)
            .toList();
        return ProtectedPaths.of(SourcePaths.of(allPaths), Excludes.of(allExcludes));
    }
}
