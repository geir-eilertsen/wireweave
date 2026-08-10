package net.vaier.domain;

/**
 * What Vaier has already told admins about one filesystem's <b>remote disk pressure</b>: the
 * {@link DiskPressureBand} the last alert went out at.
 *
 * <p>A state exists only while the filesystem is in pressure. Dropping back below its threshold removes it,
 * which is what lets a disk that recovers and re-fills be alerted about again from the bottom of the ladder.
 *
 * <p>It is <b>persisted</b>, and that is the whole point. Held in memory it was wiped by every redeploy —
 * several a day here — so every sweep looked like the first observation of that filesystem, and a filesystem
 * that was <em>already</em> above its threshold when Vaier started could never produce a crossing and never
 * alerted at all. The Vaier server's own root filesystem reached 89% against an 80% threshold in exactly
 * that silence.
 *
 * <p>Keyed on {@link MachineId}, never on the machine's name. {@code lan-servers.yml} really does hold two
 * machines both called "Printer": keyed on the name they shared one slot and swallowed each other's
 * transitions, and a rename silently reset a disk's escalation state.
 *
 * @param machineId    the machine the filesystem is on
 * @param mountPoint   where the filesystem is mounted
 * @param notifiedBand the band the most recent alert for this filesystem went out at
 */
public record DiskPressureState(MachineId machineId, String mountPoint, DiskPressureBand notifiedBand) {

    public DiskPressureState {
        if (machineId == null) {
            throw new IllegalArgumentException("Disk pressure state needs a machine id");
        }
        if (mountPoint == null || mountPoint.isBlank()) {
            throw new IllegalArgumentException("Disk pressure state needs a mount point");
        }
        if (notifiedBand == null) {
            throw new IllegalArgumentException("Disk pressure state needs the band it was notified at");
        }
    }

    /** Whether this state is the one for {@code mountPoint} on {@code machineId}. */
    public boolean isFor(MachineId otherMachineId, String otherMountPoint) {
        return machineId.isSameAs(otherMachineId) && mountPoint.equals(otherMountPoint);
    }

    /** Whether {@code band} is worse than what admins were last told, and so worth telling them again. */
    public boolean isEscalatedBy(DiskPressureBand band) {
        return band.isHigherThan(notifiedBand);
    }
}
