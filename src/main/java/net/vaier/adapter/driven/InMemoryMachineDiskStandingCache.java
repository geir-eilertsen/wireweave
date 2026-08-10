package net.vaier.adapter.driven;

import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForHoldingMachineDiskStandings;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory home for the fleet's <b>machine disk standing</b>s — the sibling of
 * {@link InMemorySshServerPresenceCache}, and written on the very same 5-minute sweep:
 * {@code RemoteDiskWatcher} records what its {@code df} already told it, and the Explorer's fleet listing
 * reads the whole fleet's standings back in one memory-backed request that wakes nothing.
 *
 * <p>Keyed by {@link MachineId}, never by name, so a rename never loses or crosses a machine's disks.
 */
@Component
public class InMemoryMachineDiskStandingCache implements ForHoldingMachineDiskStandings {

    private final Map<MachineId, MachineDiskStanding> standings = new ConcurrentHashMap<>();

    @Override
    public Optional<MachineDiskStanding> record(MachineDiskStanding standing) {
        return Optional.ofNullable(standings.put(standing.machineId(), standing));
    }

    @Override
    public Optional<MachineDiskStanding> forget(MachineId machineId) {
        return Optional.ofNullable(standings.remove(machineId));
    }

    @Override
    public List<MachineDiskStanding> getAll() {
        return List.copyOf(standings.values());
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        standings.keySet().retainAll(machineIds);
    }
}
