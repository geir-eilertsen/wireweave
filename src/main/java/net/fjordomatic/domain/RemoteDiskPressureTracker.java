package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForPersistingDiskPressureState;

import java.util.Optional;

/**
 * Decides, per filesystem, whether admins should hear about its <b>remote disk pressure</b> right now. It
 * owns the whole rule — first observation, escalation, suppression, recovery — and reaches its own state
 * through {@link ForPersistingDiskPressureState}; the watcher hands the port in and then only decides
 * <em>whom to tell</em>.
 *
 * <p><b>The silence this class was rewritten to end.</b> The Fjord server's own root filesystem reached 89%
 * against an 80% threshold and not one email was ever sent, because two things compounded:
 * <ul>
 *   <li>the state was a boolean latch whose <em>first</em> observation was always a silent baseline, so a
 *       filesystem that was <em>already</em> above its threshold could never produce a crossing; and</li>
 *   <li>the latch lived in a field on the scheduled watcher, wiped by every redeploy — several a day —
 *       so every sweep was that silent first observation, forever.</li>
 * </ul>
 * Neither fix works alone: persisting the latch on its own would have converted the bug from "never alerts"
 * into "never alerts, permanently". So the state is persisted <em>and</em> the first observation speaks —
 * which is only safe because, with the state outliving the process, "first ever" now genuinely means first
 * ever rather than first since the last deploy.
 *
 * <p><b>Escalation is by band, never by timer.</b> A filesystem alerted about at 86% goes on filling; if
 * nothing more is ever said, the one email that went out at 86% is all the operator gets for a disk that
 * ends at 99%. The answer is <em>not</em> to re-send every few hours — that pages on the passage of time,
 * which is precisely the heartbeat noise this project refuses to make. It is to speak again when the disk
 * gets materially worse: when it climbs into a higher {@link DiskPressureBand}. 86 → 89 is silence; 89 → 91
 * is an email.
 *
 * <p><b>Slipping back down a band is not a reset.</b> A disk wobbling either side of an edge (91 → 89 → 91)
 * never left pressure, so nothing new has happened and it must not page on every wobble. Only a genuine
 * recovery — back below the threshold, which is the {@link Outcome#RECOVERED} email — clears the state and
 * puts the ladder back at the bottom, so a disk that recovers and re-fills is alerted about again.
 *
 * <p>Keyed on {@link MachineId} and mount point together. Not on the machine's <em>name</em>: two machines
 * in this fleet are both called "Printer", so a name-keyed tracker had them sharing one slot and swallowing
 * each other's transitions — and a rename silently reset a disk's escalation state.
 */
public class RemoteDiskPressureTracker {

    /** What Fjord should do about one filesystem this poll. */
    public enum Outcome {
        /** Not in pressure and never was — nothing to say. */
        QUIET,
        /** In pressure for the first time, or worse than admins were last told — send the alert. */
        ALERT,
        /** Still in pressure, still in the band already alerted at — stay quiet, but say so in the log. */
        SUPPRESSED,
        /** Back below its threshold after having been in pressure — send the recovery. */
        RECOVERED
    }

    /**
     * What the tracker decided about one filesystem.
     *
     * @param outcome      what Fjord should do
     * @param band         the band this reading falls into
     * @param notifiedBand the band admins were last told about, empty when they have never been told
     */
    public record Verdict(Outcome outcome, DiskPressureBand band, Optional<DiskPressureBand> notifiedBand) { }

    private final ForPersistingDiskPressureState states;

    public RemoteDiskPressureTracker(ForPersistingDiskPressureState states) {
        this.states = states;
    }

    /**
     * Record a reading of {@code filesystem} on {@code machineId} — already judged {@code breaching} or not
     * by {@link RemoteDiskUsage#judge} — and decide what admins should hear.
     */
    public synchronized Verdict observe(MachineId machineId, RemoteDiskUsage filesystem, boolean breaching) {
        String mountPoint = filesystem.mountPoint();
        Optional<DiskPressureState> stored = states.find(machineId, mountPoint);
        Optional<DiskPressureBand> notifiedBand = stored.map(DiskPressureState::notifiedBand);
        DiskPressureBand band = DiskPressureBand.of(filesystem.usedPercent());

        if (!breaching) {
            if (stored.isEmpty()) {
                return new Verdict(Outcome.QUIET, band, notifiedBand);
            }
            states.clear(machineId, mountPoint);
            return new Verdict(Outcome.RECOVERED, band, notifiedBand);
        }
        if (stored.isPresent() && !stored.get().isEscalatedBy(band)) {
            return new Verdict(Outcome.SUPPRESSED, band, notifiedBand);
        }
        states.save(new DiskPressureState(machineId, mountPoint, band));
        return new Verdict(Outcome.ALERT, band, notifiedBand);
    }
}
