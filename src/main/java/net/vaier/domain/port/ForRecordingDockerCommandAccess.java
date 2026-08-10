package net.vaier.domain.port;

import net.vaier.domain.DockerCommandAccess;
import net.vaier.domain.MachineId;

import java.util.Set;

/**
 * Driven port for keeping what Vaier last saw of a machine's {@link DockerCommandAccess} — the write half,
 * written by the sweep that observes it. Mirrors {@code ForRecordingSshServerPresence}: same trip, same
 * shape, another fact about the machine rather than about anything on it.
 */
public interface ForRecordingDockerCommandAccess {

    /** Keep {@code access} as what Vaier last saw for {@code machineId}. */
    void record(MachineId machineId, DockerCommandAccess access);

    /**
     * Forget every machine outside {@code machineIds} — a machine deleted while Vaier was running must not
     * leave its last-seen Docker access behind forever.
     */
    void retainOnly(Set<MachineId> machineIds);
}
