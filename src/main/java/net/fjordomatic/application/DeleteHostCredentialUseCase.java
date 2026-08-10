package net.fjordomatic.application;

import net.fjordomatic.domain.MachineId;

public interface DeleteHostCredentialUseCase {

    /** Remove the host credential held for {@code machineId}; a no-op when none exists. */
    void deleteHostCredential(MachineId machineId);
}
