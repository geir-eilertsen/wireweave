package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineNetworks;
import net.fjordomatic.domain.port.ForCachingMachineNetworks;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for {@link MachineNetworks} (#333), the sibling of
 * {@link InMemorySshServerPresenceCache}: the scheduled disk sweep — which already reaches every
 * SSH-accessible, credentialed machine — writes what it read here, and the Explorer's nudges endpoint
 * serves from here. That is the whole point of the cache: opening a machine in the Explorer must never
 * cost an SSH round-trip, and every other signal that endpoint composes is already free.
 *
 * <p>Ephemeral on purpose. A reading is a fact about a network that can change under Fjord's feet, so
 * losing it on a restart is correct — the next sweep re-reads it within five minutes, and until then
 * Fjord simply says nothing rather than offering a stale network. Nothing here is state an operator
 * chose, so there is nothing to persist.
 */
@Component
public class InMemoryMachineNetworkCache implements ForCachingMachineNetworks {

    private final Map<MachineId, MachineNetworks> networks = new ConcurrentHashMap<>();

    @Override
    public void record(MachineId machineId, MachineNetworks reading) {
        networks.put(machineId, reading);
    }

    @Override
    public MachineNetworks getNetworks(MachineId machineId) {
        return networks.getOrDefault(machineId, MachineNetworks.unknown());
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        networks.keySet().retainAll(machineIds);
    }
}
