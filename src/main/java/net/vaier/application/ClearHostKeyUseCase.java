package net.vaier.application;

import net.vaier.domain.MachineId;

public interface ClearHostKeyUseCase {

    /**
     * Forget the pinned SSH host key for {@code machineId}, so the next connect re-pins on first use.
     * Used to recover after a machine is legitimately rebuilt and presents a new host key.
     */
    void clearHostKey(MachineId machineId);
}
