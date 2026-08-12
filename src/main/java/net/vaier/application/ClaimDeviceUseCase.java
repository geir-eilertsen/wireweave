package net.vaier.application;

import net.vaier.domain.PeerNotFoundException;

public interface ClaimDeviceUseCase {

    /**
     * Claims the calling browser as {@code machineId}'s device and returns the opaque claim token it should
     * carry afterwards. Supersedes any earlier claim on that machine and keeps whatever position it had
     * already reported.
     *
     * <p>The machine id is a parameter here, and only here: this is the operator asserting from an
     * authorised console session which device they are on — not a device asserting its own identity.
     *
     * @throws IllegalArgumentException when {@code machineId} is not a canonical machine id.
     * @throws PeerNotFoundException    when it names no peer — a claim stored against nothing could
     *                                  never place a dot.
     */
    String claimDevice(String machineId);
}
