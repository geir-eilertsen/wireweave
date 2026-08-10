package net.fjordomatic.application;

import net.fjordomatic.domain.MachineId;

public interface DeleteLanServerUseCase {

    /** Forget the LAN server with this identity, cascading into the services published from it. */
    void delete(MachineId machineId);
}
