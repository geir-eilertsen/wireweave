package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetDiskWatchesUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.vaier.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DiskWatch;
import net.vaier.domain.DiskWatches;
import net.vaier.domain.Machine;
import net.vaier.domain.NoSshServerException;
import net.vaier.domain.RemoteDiskForecastTracker;
import net.vaier.domain.RemoteDiskPressureTracker;
import net.vaier.domain.RemoteDiskUsage;
import net.vaier.domain.SshServerPresence;
import net.vaier.domain.port.ForPersistingDiskPressureState;
import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRecordingSshServerPresence;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Polls <b>every real filesystem</b> of every SSH-accessible machine that Vaier holds a credential for and
 * emails admins when a watched one crosses its alert threshold. This covers the Vaier host itself (via
 * SSH-to-self) as well as every other machine, so there is a single disk-alert path for the whole fleet. It
 * runs a bounded {@code df} over SSH via {@link RunRemoteCommandUseCase} (never touching the SSH ports
 * directly), and a per-filesystem {@link RemoteDiskPressureTracker} decides what admins hear: the first
 * time a filesystem is ever seen in pressure, again each time it climbs into a higher
 * {@link net.vaier.domain.DiskPressureBand}, and once more when it drops back to normal. Nothing is
 * re-alerted every poll, and nothing is re-alerted on a timer either.
 *
 * <p><b>The silence this watcher was fixed for.</b> The Vaier server's own root filesystem reached 89%
 * against an 80% threshold and not one email went out. The tracker's state was a boolean latch on a field
 * of this very class: a redeploy — several a day here — wiped it, so every sweep was a "first observation",
 * and a first observation was treated as a silent baseline. A filesystem that was already above its
 * threshold could therefore never cross it, forever. The state now lives behind
 * {@link ForPersistingDiskPressureState} and the first observation speaks. Suppression is logged, so a
 * quiet disk is never again indistinguishable from an unwatched one.
 *
 * <p><b>#325.</b> It used to read {@code df -P /} — the root filesystem, and only the root filesystem. On the
 * NAS that is the fixed-size 2.3 GB DSM system partition, 88% by design and never moving, so the alert Vaier
 * could send was about a partition the operator could not act on, while {@code /volume1} — 11.6 TB, holding
 * every borg backup — was invisible and could have filled to 100% without a word. Now every filesystem is
 * read, each is judged against its own {@link DiskWatch} (watched or muted, at its own threshold or the
 * global one), and the alert <em>names the mount and its size</em>: "NAS /volume1 is at 91% (10.8 TiB, 1.0
 * TiB free)" tells the operator something they can act on; "NAS is at 88%" told them nothing.
 *
 * <p>Machines without SSH access or without a stored credential are skipped silently, so Vaier never mounts a
 * failed-auth storm. A machine that is unreachable, whose {@code df} times out or exits non-zero, or returns
 * output that cannot be parsed is degraded, not alarmed — the trackers are left untouched so a transient
 * failure can never masquerade as a full disk.
 *
 * <p>The same readings feed a second, forward-looking consumer: a per-filesystem
 * {@link RemoteDiskForecastTracker} projects the disk-fill <b>runway</b> from the recent trend and emails an
 * early warning when a filesystem is projected to fill within the forecast horizon <em>while still below</em>
 * its threshold — so a filling disk pages once as a forecast and then the level alert takes over. No extra
 * SSH round-trip: the forecast reads the readings the level check already took, and a failed or unparseable
 * {@code df} records no sample (the failure paths {@code return} before the forecast feed).
 *
 * <p>A third consumer rides the same SSH attempt: whether the machine has an SSH server listening at all.
 * A refused connect surfaces from the shared {@code SshConnector} as {@link NoSshServerException} — the one
 * failure narrow enough to record with confidence, since a timeout or an auth failure could just as easily
 * mean the machine is merely asleep. Recorded via {@link ForRecordingSshServerPresence} and, on a boundary
 * crossing, published on the {@code vpn-peers} SSE stream so the Explorer can grey out SSH-dependent
 * controls (and lift the greying the moment a later sweep reaches the machine again) without waiting for an
 * operator's click to fail. No extra SSH traffic: this reads the very attempt {@code checkMachine} already
 * makes.
 */
@Component
@Slf4j
public class RemoteDiskWatcher {

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase credentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    private final NotifyAdminsOfDiskFillForecastUseCase forecastNotifier;
    private final GetDiskWatchesUseCase diskWatches;
    private final ConfigResolver configResolver;
    private final Clock clock;
    private final ForRecordingSshServerPresence sshPresenceRecorder;
    private final ForPublishingEvents eventPublisher;
    private final RemoteDiskPressureTracker tracker;
    private final RemoteDiskForecastTracker forecastTracker = new RemoteDiskForecastTracker();

    // The stream the Explorer already holds open for fleet liveness (peers, LAN reachability) — piggybacking
    // here costs no new connection and no timer.
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSH_SERVER_PRESENCE_SSE_EVENT = "ssh-server-presence-changed";

    public RemoteDiskWatcher(GetMachinesUseCase machines,
                             GetHostCredentialUseCase credentials,
                             RunRemoteCommandUseCase remoteCommand,
                             NotifyAdminsOfRemoteDiskPressureUseCase notifier,
                             NotifyAdminsOfDiskFillForecastUseCase forecastNotifier,
                             GetDiskWatchesUseCase diskWatches,
                             ConfigResolver configResolver,
                             Clock clock,
                             ForRecordingSshServerPresence sshPresenceRecorder,
                             ForPublishingEvents eventPublisher,
                             ForPersistingDiskPressureState diskPressureState) {
        this.machines = machines;
        this.credentials = credentials;
        this.remoteCommand = remoteCommand;
        this.notifier = notifier;
        this.forecastNotifier = forecastNotifier;
        this.diskWatches = diskWatches;
        this.configResolver = configResolver;
        this.clock = clock;
        this.sshPresenceRecorder = sshPresenceRecorder;
        this.eventPublisher = eventPublisher;
        // The domain owns the port call; this watcher only hands it in. Its state is on disk precisely so
        // that a redeploy — several a day here — no longer wipes what admins have already been told.
        this.tracker = new RemoteDiskPressureTracker(diskPressureState);
    }

    @Scheduled(fixedDelay = 300000)
    public void checkRemoteDiskUsage() {
        int globalThreshold = configResolver.getDiskMonitorThresholdPercent();
        DiskWatches watches = diskWatches.getDiskWatches();
        List<Machine> allMachines = machines.getAllMachines();
        for (Machine machine : allMachines) {
            checkMachine(machine, watches, globalThreshold);
        }
        // A machine deleted while Vaier was running must not leave its SSH-server presence behind forever.
        sshPresenceRecorder.retainOnly(allMachines.stream().map(Machine::id).collect(Collectors.toSet()));
    }

    private void checkMachine(Machine machine, DiskWatches watches, int globalThreshold) {
        if (!machine.effectiveSshAccess()) {
            return;
        }
        if (credentials.getHostCredential(machine.id()).isEmpty()) {
            return;
        }
        try {
            CommandResult result = remoteCommand.run(machine.id(), RemoteDiskUsage.DF_COMMAND);
            // Reaching a CommandResult at all — whatever df itself said — already proves the SSH session
            // connected and authenticated, so the server is present regardless of df's own exit status.
            recordSshServerPresent(machine);
            if (result.timedOut() || result.exitCode() != 0) {
                log.debug("Remote df on {} failed (exit={}, timedOut={}); skipping",
                        machine.name(), result.exitCode(), result.timedOut());
                return;
            }
            List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList(machine.name(), result.stdout());
            if (filesystems.isEmpty()) {
                log.debug("Could not parse remote df output from {}; skipping", machine.name());
                return;
            }
            for (RemoteDiskUsage filesystem : filesystems) {
                checkFilesystem(machine, filesystem, watches, globalThreshold);
            }
        } catch (NoSshServerException e) {
            // The one failure narrow enough to record with confidence — see NoSshServerException.
            recordSshServerAbsent(machine);
        } catch (Exception e) {
            // Every other failure (timeout, auth, host-key mismatch, an unreachable network) is ambiguous —
            // it could just as easily mean the machine is asleep — so it must never move the tracker either
            // way.
            log.debug("Remote disk check failed for {}: {}", machine.name(), e.getMessage());
        }
    }

    /** Records that the SSH server is gone and, only on the boundary crossing, wakes the Explorer. */
    private void recordSshServerAbsent(Machine machine) {
        SshServerPresence previous = sshPresenceRecorder.record(machine.id(), SshServerPresence.ABSENT);
        log.debug("No SSH server detected on {} ({})", machine.name(), machine.id());
        if (previous != SshServerPresence.ABSENT) {
            eventPublisher.publish(SSE_TOPIC, SSH_SERVER_PRESENCE_SSE_EVENT, "");
        }
    }

    /** Records that the SSH server answered and, only when recovering from a known-absent state, wakes it. */
    private void recordSshServerPresent(Machine machine) {
        SshServerPresence previous = sshPresenceRecorder.record(machine.id(), SshServerPresence.PRESENT);
        if (previous == SshServerPresence.ABSENT) {
            eventPublisher.publish(SSE_TOPIC, SSH_SERVER_PRESENCE_SSE_EVENT, "");
        }
    }

    /**
     * One filesystem, judged against its own watch. The watcher decides nothing: it asks the domain once —
     * {@code RemoteDiskUsage.judge} resolves mute, the filesystem's own threshold and the global fallback —
     * and then only decides <em>whom to tell</em>. {@code MachineService.getDiskUsage} asks the very same
     * method to feed the Explorer, which is what guarantees the alert email and the tree can never disagree
     * about a disk.
     *
     * <p>A {@link RemoteDiskUsage.DiskVerdict#silent() silent} filesystem is skipped whole: no alert, and no
     * forecast either. That is the domain's rule, not a convenience here.
     */
    private void checkFilesystem(Machine machine, RemoteDiskUsage filesystem, DiskWatches watches,
                                 int globalThreshold) {
        DiskWatch watch = watches.forFilesystem(machine.id(), filesystem.mountPoint());
        RemoteDiskUsage.DiskVerdict verdict = filesystem.judge(watch, globalThreshold);
        if (verdict.silent()) {
            return;
        }
        int threshold = verdict.thresholdPercent();

        RemoteDiskPressureTracker.Verdict pressure =
            tracker.observe(machine.id(), filesystem, verdict.breaching());
        switch (pressure.outcome()) {
            case ALERT -> notifier.notifyAdminsOfRemoteDiskPressure(filesystem, threshold);
            case RECOVERED -> notifier.notifyAdminsOfRemoteDiskRecovery(filesystem, threshold);
            // Staying quiet has to be visible. The old no-op branch logged nothing at all, so a disk sitting
            // at 89% in total silence looked exactly like a disk nobody was watching — and that is precisely
            // what happened. An operator must be able to tell the two apart from the log alone.
            case SUPPRESSED -> log.info(
                "{} {} is at {}% — above its {}% threshold, already notified at band {}; staying quiet",
                machine.name(), filesystem.mountPoint(), filesystem.usedPercent(), threshold,
                pressure.notifiedBand().map(Object::toString).orElse("none"));
            case QUIET -> { /* below its threshold and never alerted about; nothing to say */ }
        }

        // Feed the same reading to the trend/forecast consumer (no extra SSH round-trip). The tracker decides
        // what to say: an early warning on the way in, and — only on a genuine recovery below the threshold —
        // an all-clear; a hand-off to the disk-pressure alert above yields neither. The threshold handed in
        // is this filesystem's own, so the forecast hands off at exactly the level the pressure alert fires
        // at.
        RemoteDiskForecastTracker.Observation forecast = forecastTracker.observe(
            machine.id(), filesystem, clock.instant(), threshold);
        forecast.earlyWarning().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecast);
        forecast.cleared().ifPresent(forecastNotifier::notifyAdminsOfDiskFillForecastCleared);
    }
}
