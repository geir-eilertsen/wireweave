package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.BackupJob;
import net.vaier.domain.ProtectedPaths;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForResolvingMachineIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BackupJobProtectedPathsAdapterTest {

    static final class InMemoryJobs implements ForPersistingBackupJobs {
        final List<BackupJob> store = new ArrayList<>();
        @Override public List<BackupJob> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupJob> getByName(String name) {
            return store.stream().filter(j -> j.name().equals(name)).findFirst();
        }
        @Override public List<BackupJob> getByMachine(MachineId machineId) {
            return store.stream().filter(j -> j.machineId().equals(machineId)).toList();
        }
        @Override public void save(BackupJob j) { store.removeIf(x -> x.name().equals(j.name())); store.add(j); }
        @Override public void deleteByName(String name) { store.removeIf(j -> j.name().equals(name)); }
    }

    InMemoryJobs jobs;
    BackupJobProtectedPathsAdapter adapter;

    @BeforeEach
    void setUp() {
        jobs = new InMemoryJobs();
        // The Explorer asks by name; the store is keyed by identity. The stub is the one seam that crosses.
        adapter = new BackupJobProtectedPathsAdapter(jobs, new ForResolvingMachineIds() {
            @Override public Optional<MachineId> idForName(String machineName) {
                return Optional.of(TestMachineIds.of(machineName));
            }
            @Override public Optional<String> nameForId(MachineId machineId) {
                return Optional.empty();
            }
        });
    }

    private BackupJob job(String name, List<String> sources, List<String> excludes) {
        return new BackupJob(name, TestMachineIds.of("Apalveien 5"), "apalveien-5", sources, excludes,
            7, 4, 6, "zstd,6", true, false);
    }

    @Test
    void aMachineWithNoJobProtectsNothing() {
        assertThat(adapter.protectedPathsFor("Apalveien 5").isEmpty()).isTrue();
    }

    @Test
    void theJobsSourcePathsAreProtected() {
        jobs.save(job("apalveien-5", List.of("/home"), List.of()));

        assertThat(adapter.protectedPathsFor("Apalveien 5").covers("/home/openhab")).isTrue();
    }

    @Test
    void anExcludedFolderIsNotProtected_soTheExplorerCannotKeepShowingItAsBackedUp() {
        // The second half of the bug: reading only the source paths meant an excluded folder still wore a full
        // shield, and the fix to "stop backing up" would have looked like it did nothing at all.
        jobs.save(job("apalveien-5", List.of("/home"), List.of("/home/openhab/userdata/logs")));

        ProtectedPaths paths = adapter.protectedPathsFor("Apalveien 5");

        assertThat(paths.covers("/home/openhab/userdata/logs")).isFalse();
        assertThat(paths.covers("/home/openhab/userdata/logs/openhab.log")).isFalse();
        assertThat(paths.covers("/home/openhab/userdata")).isTrue();
    }

    @Test
    void severalJobsOnOneMachineAreReadAsOneProtection() {
        jobs.save(job("apalveien-5", List.of("/home"), List.of("/home/openhab")));
        jobs.save(job("apalveien-5-etc", List.of("/etc"), List.of()));

        ProtectedPaths paths = adapter.protectedPathsFor("Apalveien 5");

        assertThat(paths.covers("/etc/nginx")).isTrue();
        assertThat(paths.covers("/home/geir")).isTrue();
        assertThat(paths.covers("/home/openhab")).isFalse();
    }
}
