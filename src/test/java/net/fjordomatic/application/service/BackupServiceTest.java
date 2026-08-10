package net.fjordomatic.application.service;

import net.fjordomatic.domain.DeviceCategory;
import net.fjordomatic.domain.Machine;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.BackupAsRootOutcome;
import net.fjordomatic.domain.BackupJob;
import net.fjordomatic.domain.BackupRepository;
import net.fjordomatic.domain.BackupRun;
import net.fjordomatic.domain.BackupServer;
import net.fjordomatic.domain.NotFoundException;
import net.fjordomatic.domain.Unprotection;
import net.fjordomatic.domain.port.ForPersistingBackupJobs;
import net.fjordomatic.domain.port.ForPersistingBackupRepositories;
import net.fjordomatic.domain.port.ForPersistingBackupServers;
import net.fjordomatic.domain.port.ForReadyingBackupClients;
import net.fjordomatic.domain.port.ForReadyingBackupClients.ReadyingOutcome;
import net.fjordomatic.domain.port.ForRecordingBackupRuns;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceTest {

    InMemoryRepos repositories;
    InMemoryServers servers;
    InMemoryJobs jobs;
    InMemoryRunRecorder runs;
    ForReadyingBackupClients readier;
    BackupService service;

    static final class InMemoryServers implements ForPersistingBackupServers {
        final List<BackupServer> store = new ArrayList<>();
        @Override public List<BackupServer> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupServer> getByName(String name) {
            return store.stream().filter(s -> s.name().equals(name)).findFirst();
        }
        @Override public void save(BackupServer s) {
            store.removeIf(x -> x.name().equals(s.name())); store.add(s);
        }
        @Override public void deleteByName(String name) { store.removeIf(s -> s.name().equals(name)); }
    }

    static final class InMemoryRepos implements ForPersistingBackupRepositories {
        final List<BackupRepository> store = new ArrayList<>();
        @Override public List<BackupRepository> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupRepository> getByName(String name) {
            return store.stream().filter(r -> r.name().equals(name)).findFirst();
        }
        @Override public void save(BackupRepository r) {
            store.removeIf(x -> x.name().equals(r.name())); store.add(r);
        }
        @Override public void deleteByName(String name) { store.removeIf(r -> r.name().equals(name)); }
    }

    static final class InMemoryJobs implements ForPersistingBackupJobs {
        final List<BackupJob> store = new ArrayList<>();
        @Override public List<BackupJob> getAll() { return List.copyOf(store); }
        @Override public Optional<BackupJob> getByName(String name) {
            return store.stream().filter(j -> j.name().equals(name)).findFirst();
        }
        @Override public List<BackupJob> getByMachine(MachineId machineId) {
            return store.stream().filter(j -> j.machineId().equals(machineId)).toList();
        }
        @Override public void save(BackupJob j) { store.removeIf(x -> x.machineId().equals(j.machineId())); store.add(j); }
        @Override public void deleteByMachine(MachineId machineId) { store.removeIf(j -> j.machineId().equals(machineId)); }
    }

    static final class InMemoryRunRecorder implements ForRecordingBackupRuns {
        final List<BackupRun> recorded = new ArrayList<>();
        @Override public void record(BackupRun run) { recorded.add(run); }
        @Override public Optional<BackupRun> latestForMachine(MachineId machineId) {
            return recorded.stream().filter(r -> r.machineId().equals(machineId)).reduce((a, b) -> b);
        }
        @Override public List<BackupRun> getAll() { return List.copyOf(recorded); }
    }

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepos();
        servers = new InMemoryServers();
        jobs = new InMemoryJobs();
        runs = new InMemoryRunRecorder();
        readier = mock(ForReadyingBackupClients.class);
        service = new BackupService(repositories, servers, jobs, runs, readier);
    }

    /**
     * A machine whose id is derived from its name, so the job the service creates and the assertions that
     * read it back agree about which machine is meant without threading a generated id through the test.
     */
    private Machine machine(String name) {
        return new Machine(TestMachineIds.of(name), name,
            MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null, null,
            null, false, null, DeviceCategory.SERVER, null);
    }

    private BackupServer server() {
        return new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
    }

    private BackupRepository repo() {
        return new BackupRepository("nas-borg", "nas-borg", "./colina", "s3cr3t", false);
    }

    private BackupJob job() {
        return new BackupJob("colina-home", TestMachineIds.of("Colina 27"), "nas-borg",
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false);
    }

    @Test
    void crudRepositoriesAndJobs() {
        service.saveBackupRepository(repo());
        assertThat(service.getBackupRepositories()).containsExactly(repo());

        service.saveBackupJob(job());
        assertThat(service.getBackupJobs()).containsExactly(job());

        service.deleteBackupJob(TestMachineIds.of("Colina 27"));
        assertThat(service.getBackupJobs()).isEmpty();

        service.deleteBackupRepository("nas-borg");
        assertThat(service.getBackupRepositories()).isEmpty();
    }

    @Test
    void crudBackupServers() {
        assertThat(service.getBackupServers()).isEmpty();

        service.saveBackupServer(server());
        assertThat(service.getBackupServers()).containsExactly(server());

        service.deleteBackupServer("nas-borg");
        assertThat(service.getBackupServers()).isEmpty();
    }

    @Test
    void savingABackupServerWithTheSameNameReplacesIt() {
        service.saveBackupServer(server());
        BackupServer moved = new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.9", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
        service.saveBackupServer(moved);

        assertThat(service.getBackupServers()).containsExactly(moved);
    }

    @Test
    void latestForMachineIsServedFromTheRunRecorderPort() {
        // GetBackupRunsUseCase reads purely from the driven run-recorder port — no SSH, no rest layer.
        assertThat(service.latestForMachine(TestMachineIds.of("Colina 27"))).isEmpty();

        BackupRun older = BackupRun.started(job(), "run-1", Instant.parse("2026-07-07T02:00:00Z"));
        BackupRun newer = BackupRun.started(job(), "run-2", Instant.parse("2026-07-08T02:00:00Z"));
        runs.record(older);
        runs.record(newer);

        assertThat(service.latestForMachine(TestMachineIds.of("Colina 27"))).contains(newer);
    }

    // --- accepting "back up as root" is one action: the grant and the flag (#334) ---

    @Test
    void enableBackupAsRoot_savesTheFlippedJobWhenTheMachineGrantsRootBorg() {
        service.saveBackupRepository(repo());
        service.saveBackupJob(job());
        when(readier.canBackUpAsRoot(TestMachineIds.of("Colina 27"))).thenReturn(true);

        BackupAsRootOutcome outcome = service.enableBackupAsRoot(TestMachineIds.of("Colina 27"));

        assertThat(outcome.granted()).isTrue();
        assertThat(outcome.job().backupAsRoot()).isTrue();
        assertThat(service.getBackupJobs()).singleElement()
            .satisfies(saved -> assertThat(saved.backupAsRoot()).isTrue());
    }

    @Test
    void enableBackupAsRoot_savesNothingWhenTheGrantIsNotThereYet() {
        // The install is detached; a stored flag would be a promise Fjord cannot keep tonight.
        service.saveBackupRepository(repo());
        service.saveBackupJob(job());
        when(readier.canBackUpAsRoot(TestMachineIds.of("Colina 27"))).thenReturn(false);
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(true, false, null, "Preparing client on Colina 27"));

        BackupAsRootOutcome outcome = service.enableBackupAsRoot(TestMachineIds.of("Colina 27"));

        assertThat(outcome.granted()).isFalse();
        assertThat(outcome.readying().started()).isTrue();
        assertThat(service.getBackupJobs()).singleElement()
            .satisfies(saved -> assertThat(saved.backupAsRoot()).isFalse());
    }

    @Test
    void enableBackupAsRoot_onAMachineWithNoJobIsNotFound() {
        assertThatThrownBy(() -> service.enableBackupAsRoot(TestMachineIds.of("Nowhere")))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void theFleetHasAtMostOneBackupServer() {
        // (c) first server on an empty store is allowed.
        service.saveBackupServer(server());
        assertThat(service.getBackupServers()).containsExactly(server());

        // (b) re-saving the SAME-named server (an edit) still replaces in place.
        BackupServer moved = new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.9", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
        service.saveBackupServer(moved);
        assertThat(service.getBackupServers()).containsExactly(moved);

        // (a) a second, differently-named server is rejected and not stored.
        BackupServer another = new BackupServer("other-borg", TestMachineIds.of("Other"), "192.168.3.4", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", true);
        assertThatThrownBy(() -> service.saveBackupServer(another))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nas-borg");

        assertThat(service.getBackupServers()).containsExactly(moved);
    }

    @Test
    void savingAJobForAnUnknownRepositoryIsRejected() {
        // No repository saved yet -> the job references a repository that does not exist.
        assertThatThrownBy(() -> service.saveBackupJob(job()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nas-borg");

        assertThat(service.getBackupJobs()).isEmpty();
    }

    // --- Just select and back up: get-or-create semantics ---

    @Test
    void protectGetsOrCreatesRepositoryAndJobWithABackendPassphrase() {
        service.saveBackupServer(server());

        BackupJob created = service.protect(machine("Colina 27"), List.of("/home/geir")).job();

        // The repository is named after the machine's identity, on the single server, with a strong
        // backend-generated passphrase (never taken from a client).
        BackupRepository repo = repositories.getByName(TestMachineIds.of("Colina 27").value()).orElseThrow();
        assertThat(repo.serverName()).isEqualTo("nas-borg");
        assertThat(repo.passphrase()).matches("[A-Za-z0-9]{32}");
        // The job is created for the machine, referencing that repository, with the retention defaults.
        assertThat(created.machineId()).isEqualTo(TestMachineIds.of("Colina 27"));
        assertThat(created.repositoryName()).isEqualTo(TestMachineIds.of("Colina 27").value());
        assertThat(created.sourcePaths()).containsExactly("/home/geir");
        assertThat(created.keepDaily()).isEqualTo(7);
        assertThat(created.enabled()).isTrue();
    }

    @Test
    void protectNamesTheRepositoryAfterTheMachinesIdentity() {
        // A repository is a directory on the backup server, and its name is what Fjord keys it by. Named
        // after the machine, two machines called "NAS" competed for one directory; named after the machine's
        // identity, they cannot. What a person calls each store is a separate question, answered by
        // BackupStoreLabel on the screen and by the survival kit when there is no screen.
        service.saveBackupServer(server());
        Machine nas = machine("NAS");

        BackupJob created = service.protect(nas, List.of("/home")).job();

        assertThat(created.repositoryName()).isEqualTo(nas.id().value());
        assertThat(service.getBackupRepositories()).extracting(BackupRepository::name)
            .containsExactly(nas.id().value());
    }

    @Test
    void protectGivesASecondMachineOfTheSameNameItsOwnRepositoryAndJob() {
        // Machine names stopped needing to be unique (§6.22). Two machines called "NAS" must not share a
        // borg repository — that would mix two machines' archives — nor a job, since the store upserts and
        // the loser would silently stop being backed up.
        service.saveBackupServer(server());
        Machine here = new Machine(TestMachineIds.of("nas-apalveien"), "NAS",
            MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null, null,
            null, false, null, DeviceCategory.SERVER, null);
        Machine there = new Machine(TestMachineIds.of("nas-colina"), "NAS",
            MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null, null,
            null, false, null, DeviceCategory.SERVER, null);

        BackupJob first = service.protect(here, List.of("/home")).job();
        BackupJob second = service.protect(there, List.of("/home")).job();

        assertThat(first.repositoryName()).isNotEqualTo(second.repositoryName());
        assertThat(service.getBackupJobs()).hasSize(2);
        assertThat(first.machineId()).isEqualTo(TestMachineIds.of("nas-apalveien"));
        assertThat(second.machineId()).isEqualTo(TestMachineIds.of("nas-colina"));
    }

    @Test
    void protectReusesTheExistingRepositoryAndJobForTheMachine() {
        service.saveBackupServer(server());
        service.protect(machine("Colina 27"), List.of("/home/geir"));

        service.protect(machine("Colina 27"), List.of("/etc/nginx"));

        // Still exactly one repository and one job — the second call folds into the same job.
        assertThat(repositories.getAll()).hasSize(1);
        assertThat(jobs.getAll()).hasSize(1);
        assertThat(jobs.getAll().get(0).sourcePaths())
            .containsExactlyInAnyOrder("/home/geir", "/etc/nginx");
    }

    @Test
    void protectNeverRegeneratesThePassphraseOfARepositoryThatAlreadyExists() {
        // The orphaning bug: a machine already has a borg repository whose passphrase seals it on the NAS.
        // Minting a fresh one over the top would overwrite that passphrase and leave the archives
        // undecryptable — so an existing repository is reused, never regenerated.
        service.saveBackupServer(server());
        String repoName = TestMachineIds.of("Colina 27").value();
        repositories.store.add(new BackupRepository(repoName, "nas-borg", null,
            "theRealPassphraseThatSealsTheRepo", false));
        jobs.store.add(new BackupJob("Colina 27", TestMachineIds.of("Colina 27"), repoName,
            List.of("/home/geir"), List.of(), 7, 4, 6, "zstd,6", true, false));

        service.protect(machine("Colina 27"), List.of("/etc/nginx"));

        // The repository's passphrase is untouched, and there is still exactly one repository.
        assertThat(repositories.getByName(TestMachineIds.of("Colina 27").value()).orElseThrow().passphrase())
            .isEqualTo("theRealPassphraseThatSealsTheRepo");
        assertThat(repositories.getAll()).hasSize(1);
    }

    @Test
    void protectOnAMachinesFirstBackupReadiesTheHostThroughThePortAndCarriesTheOutcome() {
        // The first back-up creates the job, and the newly-created job decides its host must be readied: the
        // service passes the driven port in, the domain calls it, and the outcome rides back on the result.
        service.saveBackupServer(server());
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(true, false, null, "Preparing client on Colina 27"));

        var outcome = service.protect(machine("Colina 27"), List.of("/home/geir"));

        assertThat(outcome.readying()).isNotNull();
        assertThat(outcome.readying().started()).isTrue();
        verify(readier).readyForBackup(TestMachineIds.of("Colina 27"));
    }

    @Test
    void protectAddingPathsToAnExistingJobDoesNotReadyTheHostAgain() {
        // Adding paths to an existing job must never re-ready a provisioned host: the port is not called and
        // the result carries no readying outcome.
        service.saveBackupServer(server());
        when(readier.readyForBackup(TestMachineIds.of("Colina 27")))
            .thenReturn(new ReadyingOutcome(true, false, null, "Preparing client on Colina 27"));
        service.protect(machine("Colina 27"), List.of("/home/geir"));   // first back-up (readies)

        var second = service.protect(machine("Colina 27"), List.of("/etc/nginx"));   // adding paths (must not re-ready)

        assertThat(second.readying()).isNull();
        // Exactly one readying across both calls — the second never re-readies.
        verify(readier, org.mockito.Mockito.times(1)).readyForBackup(any());
    }

    @Test
    void protectWithoutABackupServerConflicts() {
        assertThatThrownBy(() -> service.protect(machine("Colina 27"), List.of("/home/geir")))
            .isInstanceOf(net.fjordomatic.domain.ConflictException.class)
            .hasMessageContaining("Designate a backup server");
        assertThat(jobs.getAll()).isEmpty();
        assertThat(repositories.getAll()).isEmpty();
    }

    @Test
    void unprotectEmptyingTheJobDeletesItButKeepsTheRepository() {
        service.saveBackupServer(server());
        service.protect(machine("Colina 27"), List.of("/home/geir"));

        Unprotection result = service.unprotect(TestMachineIds.of("Colina 27"), List.of("/home/geir"));

        assertThat(result.jobDeleted()).isTrue();
        assertThat(result.job()).isNull();
        assertThat(jobs.getAll()).isEmpty();
        assertThat(repositories.getByName(TestMachineIds.of("Colina 27").value())).isPresent();
    }

    @Test
    void unprotectForAMachineWithNoJobIsANoOp() {
        Unprotection result = service.unprotect(TestMachineIds.of("Colina 27"), List.of("/home/geir"));

        assertThat(result.changed()).isFalse();
        assertThat(result.job()).isNull();
    }

    @Test
    void unprotectAPathInsideAStillProtectedFolderExcludesItAndSavesTheJob() {
        // The reported bug, at the service seam: /home stays protected, so the only way to really stop backing
        // the logs folder up is an exclude — and the job that comes back must be the one that was stored.
        service.saveBackupServer(server());
        service.protect(machine("Colina 27"), List.of("/home"));

        Unprotection result = service.unprotect(TestMachineIds.of("Colina 27"), List.of("/home/openhab/userdata/logs"));

        assertThat(result.changed()).isTrue();
        assertThat(result.job().sourcePaths()).containsExactly("/home");
        assertThat(result.job().excludes()).containsExactly("/home/openhab/userdata/logs");
        assertThat(jobs.getByMachine(TestMachineIds.of("Colina 27")).getFirst().excludes())
            .containsExactly("/home/openhab/userdata/logs");
    }

    @Test
    void unprotectSomethingNothingProtectsChangesNothing_andDoesNotEvenTouchTheStore() {
        service.saveBackupServer(server());
        service.protect(machine("Colina 27"), List.of("/home"));
        BackupJob before = jobs.getByMachine(TestMachineIds.of("Colina 27")).getFirst();

        Unprotection result = service.unprotect(TestMachineIds.of("Colina 27"), List.of("/var/log"));

        assertThat(result.changed()).isFalse();
        assertThat(jobs.getByMachine(TestMachineIds.of("Colina 27")).getFirst()).isEqualTo(before);
    }

    @Test
    void protectingAnExcludedPathAgainDropsTheExcludeSoItIsReallyBackedUp() {
        // Stop backing up X, then back up X: the folder must end up genuinely protected, not shielded on
        // screen while every borg run walks past it.
        service.saveBackupServer(server());
        service.protect(machine("Colina 27"), List.of("/home"));
        service.unprotect(TestMachineIds.of("Colina 27"), List.of("/home/openhab/userdata/logs"));

        service.protect(machine("Colina 27"), List.of("/home/openhab/userdata/logs"));

        BackupJob job = jobs.getByMachine(TestMachineIds.of("Colina 27")).getFirst();
        assertThat(job.excludes()).isEmpty();
        assertThat(job.protectedPaths().covers("/home/openhab/userdata/logs")).isTrue();
    }
}
