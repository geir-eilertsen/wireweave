package net.vaier.adapter.driven;

import net.vaier.domain.DockerCommandAccess;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForCheckingDockerCommandAccess;
import net.vaier.domain.port.ForRecordingDockerCommandAccess;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory state for {@link DockerCommandAccess}, the sibling of {@link InMemorySshServerPresenceCache}:
 * the five-minute sweep writes what its existing trip already observes, and the container scrapes read it
 * to judge <b>upgrade eligibility</b>. Keyed by {@link MachineId}, never by name, so a rename never loses
 * or crosses what Vaier knows about a machine.
 *
 * <p>Not persisted, and deliberately so — it is what Vaier <em>last saw</em>, not a setting. A restart
 * forgets it, every machine reads {@link DockerCommandAccess#UNKNOWN}, and the fleet goes on offering the
 * action until a sweep says otherwise, which is the correct behaviour for a fact nobody has re-checked.
 */
@Component
public class InMemoryDockerCommandAccessCache
    implements ForCheckingDockerCommandAccess, ForRecordingDockerCommandAccess {

    private final Map<MachineId, DockerCommandAccess> access = new ConcurrentHashMap<>();

    @Override
    public DockerCommandAccess accessFor(MachineId machineId) {
        return machineId == null ? DockerCommandAccess.UNKNOWN
            : access.getOrDefault(machineId, DockerCommandAccess.UNKNOWN);
    }

    /** Last writer wins, so a machine whose docker group has just been fixed heals on its next sweep. */
    @Override
    public void record(MachineId machineId, DockerCommandAccess value) {
        access.put(machineId, value);
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        access.keySet().retainAll(machineIds);
    }
}
