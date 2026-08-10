package net.vaier.application;

import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;

/**
 * Reach one machine over SSH, read the networks it is on, and remember them (#333). Driven by the
 * scheduled sweep that already reaches every SSH-accessible, credentialed machine — never by a page
 * paint.
 */
public interface DetectMachineNetworksUseCase {

    /**
     * Detect and remember {@code machineId}'s networks. A reading Vaier could not take changes nothing:
     * the previous reading is left alone, exactly as the disk trackers are, so a transient failure can
     * never masquerade as "this machine has no network".
     *
     * @return what was detected, or {@link MachineNetworks#unknown()} when the machine could not be read
     */
    MachineNetworks detectMachineNetworks(MachineId machineId);
}
