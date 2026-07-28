package net.vaier.application;

import net.vaier.domain.MachineId;

public interface RenameLanServerUseCase {

    /**
     * Sets the display name of the LAN server with this identity. Published services keep working — LAN
     * routes are keyed by address, not by the LAN server's name.
     *
     * <p>Addressed by identity because a rename is precisely the operation that makes a name stop being a
     * usable address: the machine answering to the old name is the one to change, and after the write it no
     * longer answers to it. Throws {@link net.vaier.domain.NotFoundException} when no machine has this id.
     */
    void rename(MachineId machineId, String newName);
}
