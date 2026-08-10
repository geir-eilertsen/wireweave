package net.vaier.application;

import net.vaier.domain.MachineId;

/**
 * Update one container to the newer image its registry now serves — the verb Vaier's update-available
 * mark had no verb attached to.
 *
 * <p>Accepted rather than performed: a pull is minutes of somebody else's bandwidth, so the call returns
 * as soon as the request is judged and the work is handed off. The outcome arrives on the fleet's SSE
 * stream as a settled container-update event, carrying the sentence to show.
 */
public interface UpdateContainerImageUseCase {

    /**
     * Update the container named {@code containerName} on the machine {@code machineId}, and return at
     * once. Everything that can be judged before any host is touched is judged before returning.
     *
     * @throws net.vaier.domain.NotFoundException       when that machine has no container of that name
     * @throws net.vaier.domain.ConflictException       when the container's update eligibility refuses it,
     *                                                  carrying the plain reason
     * @throws net.vaier.domain.NoHostCredentialException when Vaier holds no SSH credential for the machine
     */
    void updateContainerImage(MachineId machineId, String containerName);
}
