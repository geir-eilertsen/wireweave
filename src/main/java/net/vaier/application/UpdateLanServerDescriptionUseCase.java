package net.vaier.application;

import net.vaier.domain.MachineId;

public interface UpdateLanServerDescriptionUseCase {

    /**
     * Sets (or, with a blank value, clears) the free-text description of the LAN server with this identity.
     * Throws {@link net.vaier.domain.NotFoundException} when no machine has this id.
     */
    void updateDescription(MachineId machineId, String description);
}
