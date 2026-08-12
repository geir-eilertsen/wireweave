package net.vaier.domain.port;

import net.vaier.domain.DiskFillTrend;
import net.vaier.domain.MachineId;

import java.util.Optional;
import java.util.Set;

/**
 * Driven port for persisting {@link DiskFillTrend}s — the retained free-space samples of each filesystem and
 * whether admins have already been warned about it.
 *
 * <p>It exists for the same reason {@link ForPersistingDiskPressureState} does, one bug later. The trend was
 * held in a field on the scheduled watcher, so every redeploy — several a day here — threw away both the
 * samples and the already-warned latch. A projection needs days of baseline, and nothing that lives only as
 * long as the process can ever accumulate days of anything.
 *
 * <p>Absent is the healthy first-boot state, and a dropped entry costs a trend that rebuilds itself rather
 * than a warning that never comes.
 */
public interface ForPersistingDiskFillTrends {

    /** The trend held for {@code mountPoint} on {@code machineId}, if any. */
    Optional<DiskFillTrend> find(MachineId machineId, String mountPoint);

    /** Persist {@code trend}, replacing any existing trend for the same machine and mount point. */
    void save(DiskFillTrend trend);

    /**
     * Drop every trend whose machine is not in {@code machineIds}, so a machine deleted while Vaier was
     * running does not keep a week of samples forever.
     */
    void retainOnly(Set<MachineId> machineIds);
}
