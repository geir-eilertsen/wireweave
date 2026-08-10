package net.vaier.application;

import net.vaier.domain.MachineId;

public interface SetMachineSshAccessUseCase {

    /**
     * Sets the explicit SSH-access override for the machine named {@code machineId} (a VPN peer or a
     * LAN server) and returns the resulting effective state. Always writes an explicit value, so the
     * returned state equals {@code enabled}. Throws {@code NotFoundException} when no machine bears
     * that name.
     */
    boolean setMachineSshAccess(MachineId machineId, boolean enabled);
}
