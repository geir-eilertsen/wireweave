package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.SshServerPresence;
import net.vaier.domain.port.ForCheckingSshServerPresence;
import net.vaier.domain.port.ForRecordingSshServerPresence;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for {@link net.vaier.domain.SshServerPresence}, the fleet-wide sibling of
 * {@link InMemoryLanReachabilityCache}: {@code RemoteDiskWatcher} writes what its existing 5-minute sweep
 * already observes via {@link ForRecordingSshServerPresence}, and other components — the machine list read
 * that feeds the Explorer — read the current belief via {@link ForCheckingSshServerPresence}. Keyed by
 * {@link MachineId}, never by name, so a rename never loses or crosses a machine's presence history.
 */
@Component
public class InMemorySshServerPresenceCache implements ForCheckingSshServerPresence, ForRecordingSshServerPresence {

    private final Map<MachineId, SshServerPresence> presence = new ConcurrentHashMap<>();

    @Override
    public SshServerPresence getPresence(MachineId machineId) {
        return presence.getOrDefault(machineId, SshServerPresence.UNKNOWN);
    }

    @Override
    public SshServerPresence record(MachineId machineId, SshServerPresence value) {
        SshServerPresence previous = presence.put(machineId, value);
        return previous == null ? SshServerPresence.UNKNOWN : previous;
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        presence.keySet().retainAll(machineIds);
    }
}
