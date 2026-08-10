package net.fjordomatic.application;

import net.fjordomatic.domain.MachineId;

public interface UpdateLanServerDescriptionUseCase {

    /**
     * Sets (or, with a blank value, clears) the free-text description of the LAN server with this identity.
     * Throws {@link net.fjordomatic.domain.NotFoundException} when no machine has this id.
     */
    void updateDescription(MachineId machineId, String description);
}
