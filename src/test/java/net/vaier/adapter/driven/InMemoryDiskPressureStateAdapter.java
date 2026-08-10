package net.vaier.adapter.driven;

import net.vaier.domain.DiskPressureState;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingDiskPressureState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only in-memory stand-in for {@link ForPersistingDiskPressureState}. Real deployments use
 * {@link DiskPressureStateFileAdapter}; tests that only care about the <em>decisions</em> the domain makes
 * from the stored state use this so they never touch a temp directory.
 */
public class InMemoryDiskPressureStateAdapter implements ForPersistingDiskPressureState {

    private final Map<String, DiskPressureState> states = new LinkedHashMap<>();

    @Override
    public synchronized Optional<DiskPressureState> find(MachineId machineId, String mountPoint) {
        return Optional.ofNullable(states.get(key(machineId, mountPoint)));
    }

    @Override
    public synchronized void save(DiskPressureState state) {
        states.put(key(state.machineId(), state.mountPoint()), state);
    }

    @Override
    public synchronized void clear(MachineId machineId, String mountPoint) {
        states.remove(key(machineId, mountPoint));
    }

    /** Machine and mount joined on NUL — the one byte neither can contain, so no two keys collide. */
    private static String key(MachineId machineId, String mountPoint) {
        return machineId.value() + '\0' + mountPoint;
    }
}
