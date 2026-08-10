package net.vaier.application;

import net.vaier.domain.MachineId;

public interface DeleteLanServerUseCase {

    /** Forget the LAN server with this identity, cascading into the services published from it. */
    void delete(MachineId machineId);
}
