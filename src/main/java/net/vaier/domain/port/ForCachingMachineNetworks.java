package net.vaier.domain.port;

import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;

import java.util.Set;

/**
 * Driven port for Vaier's current belief about each machine's networks — the sibling of
 * {@link ForCheckingSshServerPresence} / {@link ForRecordingSshServerPresence}, and kept for the same
 * reason: the reading costs an SSH round-trip, and the surface that consumes it (the Explorer's nudges,
 * repainted every time a machine pane opens) must never pay for one.
 *
 * <p>The scheduled sweep that already reaches every credentialed machine writes here; the machines
 * read serves from here. Keyed by {@link MachineId}, never by name, so a rename never loses or crosses
 * a machine's reading.
 *
 * <p>Reader and writer are one interface here — unlike SSH-server presence — because they are one
 * consumer: {@code MachineService} both records what the sweep detected and answers what the Explorer
 * asks. Splitting it would name a boundary that does not exist.
 */
public interface ForCachingMachineNetworks {

    /** Commit the networks last read off {@code machineId}. */
    void record(MachineId machineId, MachineNetworks networks);

    /**
     * What Vaier last read off {@code machineId} — {@link MachineNetworks#unknown()} when it has never
     * managed to read it. Never null: "we do not know" is a reading, not an absence.
     */
    MachineNetworks getNetworks(MachineId machineId);

    /**
     * Drop every cached reading whose machine is not in {@code machineIds}. Called after a sweep, so a
     * deleted machine's networks cannot linger and be offered for a machine that no longer exists.
     */
    void retainOnly(Set<MachineId> machineIds);
}
