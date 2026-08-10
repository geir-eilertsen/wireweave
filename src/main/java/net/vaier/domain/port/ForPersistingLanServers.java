package net.vaier.domain.port;

import net.vaier.domain.LanServer;
import net.vaier.domain.MachineId;

import java.util.List;

public interface ForPersistingLanServers {

    /**
     * Store {@code server}, replacing the entry with the same {@link MachineId} if there is one.
     *
     * <p>By identity, never by name: a name is editable and two machines may share one, so upserting on it
     * would drop a real machine out of the store the moment a second took its name — the exact silent loss
     * identity exists to prevent.
     */
    void save(LanServer server);

    List<LanServer> getAll();

    /** Forget the machine with this identity. Unknown identity is a no-op, not an error. */
    void deleteById(MachineId machineId);
}
