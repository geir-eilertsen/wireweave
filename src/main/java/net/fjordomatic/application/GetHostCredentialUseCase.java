package net.fjordomatic.application;

import net.fjordomatic.domain.HostCredentialView;

import java.util.Optional;
import net.fjordomatic.domain.MachineId;

public interface GetHostCredentialUseCase {

    /** The redacted view of the credential held for {@code machineId}, or empty when none exists. */
    Optional<HostCredentialView> getHostCredential(MachineId machineId);
}
