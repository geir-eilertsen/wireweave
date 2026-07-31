package net.vaier.application;

import net.vaier.domain.MachineId;

import java.util.Set;

/**
 * Drop the detected networks of machines the fleet no longer has (#333). Called at the end of the sweep
 * that detects them, so a machine deleted while Vaier was running cannot leave a reading behind to be
 * offered later under a recycled identity.
 */
public interface ForgetMachineNetworksUseCase {

    /** Forget every detected reading whose machine is not in {@code machineIds}. */
    void forgetMachineNetworksExcept(Set<MachineId> machineIds);
}
