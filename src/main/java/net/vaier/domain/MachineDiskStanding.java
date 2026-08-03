package net.vaier.domain;

import lombok.Builder;
import net.vaier.domain.port.ForHoldingMachineDiskStandings;
import net.vaier.domain.port.ForPublishingEvents;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * <b>The worst thing true about one machine's disks right now</b>, reduced to what a fleet listing can say on
 * a card: which watched filesystem is closest to trouble, how full it is, what it is judged against, and how
 * many of the machine's watched filesystems are breaching.
 *
 * <p><b>Why this exists at all, and why it costs nothing.</b> {@code RemoteDiskWatcher} has swept every
 * SSH-accessible machine with {@code df -P} every five minutes since the disk alerts shipped, judged every
 * filesystem — and then thrown the readings away. So disk pressure was a thing Vaier knew and only ever
 * emailed about. This is that same reading, retained: no fleet-wide {@code df} on page load, which would
 * wake every sleeping machine to answer a question nobody asked.
 *
 * <p><b>Which filesystem is "the worst" is a decision, and it lives here.</b> It is the one with the least
 * headroom against the threshold <em>it</em> is judged by — its own, or the fleet's <b>disk alert
 * threshold</b> — never the fullest one. A {@code /boot} sitting at 92% under a 99% threshold of its own is
 * fine; a {@code /volume1} at 86% under 85% is not, and it is the one the card must name. Every verdict here
 * comes from {@link RemoteDiskUsage#judge}, the same single method the alert email asks, so the card and the
 * email can never disagree about a disk.
 *
 * <p><b>A muted filesystem is one nobody is judging</b>, so it can neither be the worst nor count towards
 * anything. A machine whose filesystems are <em>all</em> muted therefore has no standing at all — and
 * neither does one Vaier has not reached yet. That is the point of {@link #of} returning an
 * {@link Optional}: absence must draw no mark, because absence read as health is exactly how a disk at 89%
 * once sat in silence.
 *
 * @param machineId              the machine this standing is about — an identity, never a name
 * @param worstMountPoint        the mount point of the watched filesystem closest to trouble
 * @param worstUsedPercent       how full that filesystem is (0–100)
 * @param worstThresholdPercent  the threshold that filesystem is judged against — its own, or the global one
 * @param breachingFilesystems   how many of the machine's watched filesystems are above their threshold
 * @param watchedFilesystems     how many filesystems were read and judged (muted ones are neither)
 */
@Builder
public record MachineDiskStanding(MachineId machineId, String worstMountPoint, int worstUsedPercent,
                                  int worstThresholdPercent, int breachingFilesystems,
                                  int watchedFilesystems) {

    /**
     * The standing of {@code machineId}'s disks, or empty when there is nothing to stand on — no filesystem
     * was read, or every one that was is muted.
     *
     * <p>Judged filesystem by filesystem through {@link RemoteDiskUsage#judge}: mute, the filesystem's own
     * threshold and the global fallback are all resolved there and re-derived nowhere.
     */
    public static Optional<MachineDiskStanding> of(MachineId machineId, List<RemoteDiskUsage> filesystems,
                                                   DiskWatches watches, int globalThresholdPercent) {
        if (machineId == null || filesystems == null) {
            return Optional.empty();
        }
        List<Judged> judged = filesystems.stream()
            .map(filesystem -> new Judged(filesystem,
                filesystem.judge(watches.forFilesystem(machineId, filesystem.mountPoint()),
                    globalThresholdPercent)))
            .filter(entry -> !entry.verdict().silent())
            .toList();
        if (judged.isEmpty()) {
            return Optional.empty();
        }
        Judged worst = judged.stream().max(Comparator.comparingInt(Judged::headroomUsed)
            .thenComparingInt(entry -> entry.filesystem().usedPercent())
            // Two filesystems equally close to their thresholds is a real tie, and a tie that flipped from
            // sweep to sweep would push an SSE event and repaint every card for nothing.
            .thenComparing(entry -> entry.filesystem().mountPoint(), Comparator.reverseOrder()))
            .orElseThrow();
        return Optional.of(MachineDiskStanding.builder()
            .machineId(machineId)
            .worstMountPoint(worst.filesystem().mountPoint())
            .worstUsedPercent(worst.filesystem().usedPercent())
            .worstThresholdPercent(worst.verdict().thresholdPercent())
            .breachingFilesystems((int) judged.stream().filter(entry -> entry.verdict().breaching()).count())
            .watchedFilesystems(judged.size())
            .build());
    }

    /**
     * <b>Keep this reading as the machine's standing</b>, and wake an open Explorer only if it says something
     * the last one did not. Every caller that takes a judged {@code df} — the five-minute sweep, and a live
     * look at a machine's disk pane — hands it here, so there is exactly one place that decides what a fresh
     * reading does to a card.
     *
     * <p><b>Why the pane matters as much as the sweep.</b> An operator muted {@code /} on the NAS and the
     * card went on naming {@code /} as that machine's worst filesystem until the next sweep happened to run:
     * up to five minutes of a card asserting a verdict about a disk nobody is judging any more. The same
     * staleness applied to un-muting one and to moving a threshold. The fix costs no connection at all,
     * because the reading was already being taken — the disk pane re-reads the machine the moment a watch is
     * written, and that reading used to be rendered and dropped. Retaining it also means the mark self-heals
     * the moment anyone looks at a machine.
     *
     * <p>A machine with nothing left to judge is <em>forgotten</em>, not recorded as clear — {@link #of}
     * returns empty for that, and a card must draw nothing rather than freeze on its last verdict.
     *
     * <p>The publish is gated on {@link #differsFrom} for a reason worth stating: the pane is re-read on
     * every view, so an ungated publish would repaint every card in every open Explorer each time somebody
     * merely looked at a machine.
     *
     * <p><b>A reading that failed never reaches here</b>, and that is deliberate: an unreachable machine, a
     * missing credential or a refused SSH tells you nothing new about its disks, only that Vaier could not
     * look. Both callers bail before this point on failure, so the last known standing stands. Treating a
     * failure as "nothing to judge" would silently erase a machine's mark every time it went to sleep, which
     * is the #325 silence — absence read as health — in a new costume.
     */
    public static void retain(MachineId machineId, List<RemoteDiskUsage> filesystems, DiskWatches watches,
                              int globalThresholdPercent, ForHoldingMachineDiskStandings standings,
                              ForPublishingEvents events) {
        Optional<MachineDiskStanding> standing = of(machineId, filesystems, watches, globalThresholdPercent);
        boolean changed = standing
            .map(current -> current.differsFrom(standings.record(current).orElse(null)))
            .orElseGet(() -> standings.forget(machineId).isPresent());
        if (changed) {
            events.publish(SSE_TOPIC, SSE_EVENT, "");
        }
    }

    /**
     * Where a changed standing is announced: the stream the fleet page already holds open for peer liveness.
     * It lives here rather than in each caller because the sweep and the disk pane must announce the very
     * same thing on the very same stream — two copies of these two strings is one rename away from a card
     * that never refreshes and nobody noticing.
     */
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "disk-standing-changed";

    /** How this machine's disks stand — the severity the fleet listing tints its mark by. */
    public DiskStandingLevel level() {
        if (worstUsedPercent > worstThresholdPercent) {
            return DiskStandingLevel.BREACHING;
        }
        return worstUsedPercent > worstThresholdPercent - DiskPressureBand.STEP
            ? DiskStandingLevel.CLOSING
            : DiskStandingLevel.CLEAR;
    }

    /**
     * Whether this standing says anything the last one did not — the question that decides whether the fleet
     * is woken at all. {@code previous} is null for a machine never read before, and a first reading always
     * speaks.
     *
     * <p>It is a whole-value comparison on purpose: everything a standing carries is something a card draws
     * or says on hover, so anything that moved is something an open Explorer is currently getting wrong. What
     * it is <em>not</em> is a heartbeat — a sweep that finds the same disks in the same state pushes nothing,
     * which is what keeps this off the five-minute drumbeat.
     */
    public boolean differsFrom(MachineDiskStanding previous) {
        return !this.equals(previous);
    }

    /** One filesystem and the verdict already passed on it — so nothing is judged twice. */
    private record Judged(RemoteDiskUsage filesystem, RemoteDiskUsage.DiskVerdict verdict) {

        /**
         * How far this filesystem is <em>past</em> the threshold it is judged against — negative while it
         * still has headroom. The single ordering key: positive beats negative, so a breaching filesystem
         * always outranks a fuller one that is within its own generous threshold.
         */
        int headroomUsed() {
            return filesystem.usedPercent() - verdict.thresholdPercent();
        }
    }
}
