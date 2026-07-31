package net.vaier.domain.port;

import net.vaier.domain.DiskPressureState;
import net.vaier.domain.MachineId;

import java.util.Optional;

/**
 * Driven port for persisting {@link DiskPressureState} — which filesystem Vaier has already alerted admins
 * about, and at what {@link net.vaier.domain.DiskPressureBand band}.
 *
 * <p>It exists because the state used to be a field on the scheduled watcher, wiped by every redeploy. That
 * turned "alert only on a crossing" into "alert only if the disk happens to cross while this particular
 * process is up" — and a filesystem that was already above its threshold at startup crossed nothing, ever.
 * Persisting it is what makes "the first time Vaier has ever seen this disk in trouble" a statement about
 * the fleet rather than about the current JVM.
 *
 * <p>Only filesystems currently in pressure have an entry, so an empty store is the healthy state.
 */
public interface ForPersistingDiskPressureState {

    /** What admins were last told about {@code mountPoint} on {@code machineId}, if anything. */
    Optional<DiskPressureState> find(MachineId machineId, String mountPoint);

    /** Persist {@code state}, replacing any existing state for the same machine and mount point. */
    void save(DiskPressureState state);

    /** Forget the state for {@code mountPoint} on {@code machineId} — it is no longer in pressure. */
    void clear(MachineId machineId, String mountPoint);
}
