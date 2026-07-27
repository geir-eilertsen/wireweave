package net.vaier.adapter.driven;

import net.vaier.domain.BackupJob;
import net.vaier.domain.Excludes;
import net.vaier.domain.MachineId;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.SourcePaths;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForReadingProtectedPaths;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reads what a machine backs up straight from the fleet-backup job store. Translation only: it gathers the
 * source paths <em>and</em> the excludes of every job that backs up the machine and hands both to the domain
 * ({@link SourcePaths#of}, {@link Excludes#of}, {@link ProtectedPaths#of}) — it makes no coverage decision of
 * its own. A machine with no job yields an empty {@link ProtectedPaths}, so the Explorer marks nothing on it.
 *
 * <p>An id no job names likewise yields an empty {@link ProtectedPaths}: nothing is claimed to be backed up
 * for a machine that has no job. This used to be two cases rather than one — the Explorer asked by name and
 * this adapter crossed to an identity first, so a name matching nothing was its own kind of empty. Both
 * sides speak identity now, and the crossing is gone.
 *
 * <p>The excludes are not optional detail. Reading only the source paths reported an excluded folder as
 * backed up, which is the one thing a backup tool must never say about data that is in no archive.
 */
@Component
public class BackupJobProtectedPathsAdapter implements ForReadingProtectedPaths {

    private final ForPersistingBackupJobs jobs;

    public BackupJobProtectedPathsAdapter(ForPersistingBackupJobs jobs) {
        this.jobs = jobs;
    }

    @Override
    public ProtectedPaths protectedPathsFor(MachineId machineId) {
        // Both sides speak identity now, so there is no crossing left to get wrong. This adapter existed
        // partly to perform one; what remains is only the question the Explorer actually asked.
        List<BackupJob> machineJobs = jobs.getByMachine(machineId);
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
