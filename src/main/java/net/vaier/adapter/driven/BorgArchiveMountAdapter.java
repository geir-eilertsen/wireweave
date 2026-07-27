package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.BackupWorkDirResolver;
import net.vaier.domain.Archive;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.BorgCommand;
import net.vaier.domain.CommandResult;
import net.vaier.domain.MachineId;
import net.vaier.domain.MountedArchive;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForMountingArchives;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mounts a machine's past — a borg {@link Archive} — as a read-only FUSE filesystem on the machine itself,
 * over the ordinary SSH command path, so the Explorer can browse it with the same SFTP code it browses the
 * live tree with. It is the driven adapter behind {@link ForMountingArchives}: {@code ExplorerService} asks
 * for a mountpoint and never learns what borg is.
 *
 * <p>It holds no borg flags — every command string comes from {@link BorgCommand} — and no path rules — the
 * mountpoint and its coordinate mapping are {@link MountedArchive}'s. What it owns is orchestration: resolve
 * the machine's backup repository, provision the pass file, resolve the archive <em>name</em> from the
 * repository's {@code borg list} (the mountpoint is keyed by the archive <em>id</em>, but borg mounts by
 * name), and mount on demand.
 *
 * <p><b>Idempotent and lazy.</b> A directory browse calls {@link #mount} on every archive it opens, so a
 * cheap {@link BorgCommand#isMounted} probe short-circuits a warm re-browse to a single round trip — the
 * {@code borg list} + {@code borg mount} run only on a cold mount. Every mount records its last-access time
 * in an in-memory registry; {@link #unmountIdle} releases the ones untouched beyond the window, so a mount
 * never outlives its use on a fleet machine.
 *
 * <p>The work dir and the archive-name lookup are resolved through the same driven ports the run
 * orchestration uses, so there is exactly one way Vaier reaches a machine and one way it reads a repository.
 * Host-key trust is enforced by the run path (a pinned mismatch is refused; a first use is accepted), and
 * the very next SFTP listing in the same browse pins it — so this adapter does not re-pin.
 */
@Component
@Slf4j
public class BorgArchiveMountAdapter implements ForMountingArchives {

    private final ForResolvingSshTargets sshTargets;
    private final ForRunningSshCommands ssh;
    private final BackupWorkDirResolver workDirResolver;
    private final ForPersistingBackupJobs jobs;
    private final ForPersistingBackupRepositories repositories;
    private final ForPersistingBackupServers servers;
    private final Clock clock;

    /** Live mounts by mountpoint → when each was last browsed, so the sweep can release the idle ones. */
    private final Map<String, LiveMount> liveMounts = new ConcurrentHashMap<>();

    private record LiveMount(MachineId machineId, MountedArchive mounted, Instant lastAccess) {
    }

    public BorgArchiveMountAdapter(ForResolvingSshTargets sshTargets, ForRunningSshCommands ssh,
                                   BackupWorkDirResolver workDirResolver, ForPersistingBackupJobs jobs,
                                   ForPersistingBackupRepositories repositories,
                                   ForPersistingBackupServers servers,
                                   Clock clock) {
        this.sshTargets = sshTargets;
        this.ssh = ssh;
        this.workDirResolver = workDirResolver;
        this.jobs = jobs;
        this.repositories = repositories;
        this.servers = servers;
        this.clock = clock;
    }

    @Override
    public MountedArchive mount(MachineId machineId, String archiveId) {
        // The caller already holds the identity, so nothing is looked up to reach the machine. The job store
        // and the work-dir cache are keyed by the same id the target is opened with, which is what makes it
        // impossible to mount one machine's archive using another's job.
        SshTarget target = sshTargets.resolve(machineId);
        // MountedArchive.under validates the archive id (opaque hex) before any connection is opened.
        String workDir = workDirResolver.workDirFor(machineId);
        MountedArchive mounted = MountedArchive.under(workDir, archiveId);

        if (!isAlreadyMounted(target, mounted)) {
            mountCold(machineId, target, mounted, workDir, archiveId);
        }
        touch(machineId, mounted);
        return mounted;
    }

    /** The cheap probe: is this mountpoint already an archive mount? A failed probe reads as "not mounted". */
    private boolean isAlreadyMounted(SshTarget target, MountedArchive mounted) {
        try {
            CommandResult probe = ssh.run(target, BorgCommand.isMounted(mounted.mountpoint()));
            return !probe.timedOut() && BorgCommand.parseMounted(probe.stdout());
        } catch (RuntimeException e) {
            log.debug("Mount probe of {} failed; assuming not mounted: {}", mounted.mountpoint(), e.getMessage());
            return false;
        }
    }

    /**
     * A cold mount: resolve the machine's repository, provision the pass file, read the archive's name from
     * {@code borg list} (the id keys the mountpoint; borg mounts by name), then mount it. Idempotent at the
     * command level too — {@link BorgCommand#mount} reuses an already-mounted mountpoint rather than failing.
     */
    private void mountCold(MachineId machineId, SshTarget target, MountedArchive mounted,
                           String workDir, String archiveId) {
        BackupJob job = firstJobFor(machineId);
        BackupRepository repo = repositoryFor(job);
        BackupServer server = serverFor(repo);

        ensurePassFile(target, repo, workDir);
        String archiveName = archiveNameFor(target, server, repo, workDir, archiveId);

        BorgCommand.BuiltCommand mount = BorgCommand.mount(server, repo, archiveName, mounted.mountpoint(),
            workDir);
        log.info("Mounting archive {} of repository {} on {} at {}", archiveName, repo.name(), machineId,
            mounted.mountpoint());
        ssh.run(target, mount.exec());
    }

    private BackupJob firstJobFor(MachineId machineId) {
        return jobs.getByMachine(machineId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException(
                // Deliberately unnamed. This surfaces on the machine's own pane in the Explorer, where the
                // operator can already see which machine they asked about — and naming it was the only reason
                // an adapter that reaches a machine by identity had to resolve a name at all.
                "This machine has no backup job, so it has no archives to browse"));
    }

    private BackupRepository repositoryFor(BackupJob job) {
        return repositories.getAll().stream()
            .filter(r -> r.name().equals(job.repositoryName())).findFirst()
            .orElseThrow(() -> new NotFoundException(
                "Backup repository " + job.repositoryName() + " is not configured"));
    }

    private BackupServer serverFor(BackupRepository repo) {
        return servers.getAll().stream()
            .filter(s -> s.name().equals(repo.serverName())).findFirst()
            .orElseThrow(() -> new NotFoundException(
                "Backup server " + repo.serverName() + " is not configured"));
    }

    /** The archive's borg name for the id being browsed — read from {@code borg list} of the repository. */
    private String archiveNameFor(SshTarget target, BackupServer server, BackupRepository repo, String workDir,
                                  String archiveId) {
        BorgCommand.BuiltCommand list = BorgCommand.listArchives(server, repo, workDir);
        CommandResult result = ssh.run(target, list.exec());
        return Archive.parseList(result.stdout()).stream()
            .filter(a -> archiveId.equals(a.id())).map(Archive::name).findFirst()
            .orElseThrow(() -> new NotFoundException(
                "No archive " + archiveId + " in repository " + repo.name()));
    }

    /** Write-if-absent the repository pass file so borg's {@code BORG_PASSCOMMAND} can read the secret. */
    private void ensurePassFile(SshTarget target, BackupRepository repo, String workDir) {
        try {
            ssh.run(target, BorgCommand.ensurePassFile(repo, workDir).exec());
        } catch (RuntimeException e) {
            // Best-effort, mirroring the run/list path: a genuinely missing secret surfaces as a mount that
            // fails to unlock, not as an exception here.
            log.debug("Could not ensure pass file for repository {}: {}", repo.name(), e.getMessage());
        }
    }

    private void touch(MachineId machineId, MountedArchive mounted) {
        liveMounts.put(mounted.mountpoint(), new LiveMount(machineId, mounted, clock.instant()));
    }

    @Override
    public void unmountIdle(long idleWindowMillis) {
        Instant cutoff = clock.instant().minus(Duration.ofMillis(idleWindowMillis));
        for (LiveMount live : List.copyOf(liveMounts.values())) {
            if (live.lastAccess().isBefore(cutoff)) {
                releaseMount(live);
            }
        }
    }

    @Override
    public void reconcileMounts() {
        // Distinct backed-up machines: a machine may have more than one job, but its mounts all live under
        // the one work dir, so probe each machine once.
        for (MachineId machineId : jobs.getAll().stream().map(BackupJob::machineId).distinct().toList()) {
            // Reached by identity, and no longer labelled at all. Looking a name up here once gated the
            // whole reconcile, and the registry it came from reads WireGuard by shelling into a container
            // that restarts — so a machine could be skipped for the duration of a restart. A skipped
            // reconcile leaves a live borg mount holding the repository lock, and the next backup run on that
            // machine fails with a lock timeout, a symptom nowhere near its cause.
            adoptOrphanMountsOn(machineId);
        }
    }

    /**
     * Ask one machine what is really mounted under its work dir and adopt any archive mount the registry has
     * forgotten (last-accessed now, so it gets a full idle window before the sweep releases it — never
     * racing a mount the current process just made). Best-effort per machine: an unreachable host is skipped,
     * never allowed to break the reconcile of the rest of the fleet.
     */
    private void adoptOrphanMountsOn(MachineId machineId) {
        try {
            String workDir = workDirResolver.workDirFor(machineId);
            SshTarget target = sshTargets.resolve(machineId);
            CommandResult result = ssh.run(target, BorgCommand.listArchiveMounts(workDir));
            for (String mountpoint : BorgCommand.parseArchiveMounts(result.stdout())) {
                LiveMount adopted = new LiveMount(machineId, new MountedArchive(mountpoint), clock.instant());
                if (liveMounts.putIfAbsent(mountpoint, adopted) == null) {
                    log.info("Adopted orphaned archive mount {} on {} for reaping", mountpoint, machineId);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not reconcile archive mounts on {}: {}", machineId, e.getMessage());
        }
    }

    /**
     * Release every tracked mount on a graceful shutdown, so a redeploy does not strand a live {@code borg
     * mount} holding the repository lock. Best-effort — {@link #releaseMount} never throws — and a hard kill
     * that skips this is caught afterwards by {@link #reconcileMounts} on the next start.
     */
    @jakarta.annotation.PreDestroy
    public void releaseAll() {
        for (LiveMount live : List.copyOf(liveMounts.values())) {
            releaseMount(live);
        }
    }

    /**
     * Unmount one idle mount and forget it — but only when the release actually took. A failed unmount (FUSE
     * busy) or an SSH error leaves the mount <em>tracked</em>, so the next sweep retries; dropping it here
     * regardless is exactly how a {@code borg mount} orphans and holds the repository lock forever. A failure
     * is logged, not thrown — the sweep must not break.
     */
    private void releaseMount(LiveMount live) {
        try {
            SshTarget target = sshTargets.resolve(live.machineId());
            CommandResult result = ssh.run(target, BorgCommand.umount(live.mounted().mountpoint()));
            if (BorgCommand.parseUnmounted(result.stdout())) {
                liveMounts.remove(live.mounted().mountpoint());
                log.info("Released idle archive mount {} on {}", live.mounted().mountpoint(),
                    live.machineId());
            } else {
                log.warn("Idle archive mount {} on {} is still mounted after umount; keeping it tracked to "
                    + "retry", live.mounted().mountpoint(), live.machineId());
            }
        } catch (RuntimeException e) {
            log.debug("Could not release idle mount {} on {}; keeping it tracked to retry: {}",
                live.mounted().mountpoint(), live.machineId(), e.getMessage());
        }
    }
}
