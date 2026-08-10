package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;

/**
 * Read what Vaier last detected about a machine's own networks (#333) — free, from the cache the
 * scheduled sweep fills. Never reaches the machine: the Explorer repaints a machine's nudges on every
 * pane open, and this must stay as cheap as every other signal that endpoint composes.
 */
public interface GetMachineNetworksUseCase {

    /** The last reading for {@code machineId}, or {@link MachineNetworks#unknown()} if there is none. */
    MachineNetworks getMachineNetworks(MachineId machineId);
}
