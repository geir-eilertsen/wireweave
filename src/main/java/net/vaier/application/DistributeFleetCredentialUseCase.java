package net.vaier.application;

import net.vaier.domain.FleetCredentialStanding;
import net.vaier.domain.NotFoundException;

import java.util.List;

public interface DistributeFleetCredentialUseCase {

    /**
     * Write the fleet credential {@code name} onto every machine that runs a shell Vaier can reach, and
     * report where it stands on each. A machine without SSH access or without a host credential is
     * {@link net.vaier.domain.FleetCredentialState#SKIPPED} — a phone has nowhere to put the file, and
     * that is not a failure.
     *
     * @throws NotFoundException no fleet credential is stored under that name
     */
    List<FleetCredentialStanding> distributeFleetCredential(String name);
}
