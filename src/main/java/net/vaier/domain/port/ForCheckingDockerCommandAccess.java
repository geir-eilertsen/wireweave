package net.vaier.domain.port;

import net.vaier.domain.DockerCommandAccess;
import net.vaier.domain.MachineId;

/**
 * Driven port for reading what Vaier last saw of a machine's {@link DockerCommandAccess} — the read half,
 * consulted wherever a machine's containers are judged for <b>update eligibility</b>.
 *
 * <p>Always answers: a machine nobody has swept yet reads {@link DockerCommandAccess#UNKNOWN}, which is not
 * a refusal and must never be read as one.
 */
public interface ForCheckingDockerCommandAccess {

    /** What Vaier last saw of {@code machineId}'s Docker access, or {@code UNKNOWN} if it has never looked. */
    DockerCommandAccess accessFor(MachineId machineId);
}
