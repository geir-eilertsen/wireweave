package net.fjordomatic.domain.port;

import net.fjordomatic.domain.DockerCommandAccess;
import net.fjordomatic.domain.MachineId;

/**
 * Driven port for reading what Fjord last saw of a machine's {@link DockerCommandAccess} — the read half,
 * consulted wherever a machine's containers are judged for <b>update eligibility</b>.
 *
 * <p>Always answers: a machine nobody has swept yet reads {@link DockerCommandAccess#UNKNOWN}, which is not
 * a refusal and must never be read as one.
 */
public interface ForCheckingDockerCommandAccess {

    /** What Fjord last saw of {@code machineId}'s Docker access, or {@code UNKNOWN} if it has never looked. */
    DockerCommandAccess accessFor(MachineId machineId);
}
