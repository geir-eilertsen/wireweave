package net.fjordomatic.domain.port;

import net.fjordomatic.domain.HostCredential;
import net.fjordomatic.domain.MachineId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for storing the one host credential Fjord holds per machine in the credential vault,
 * keyed by {@link MachineId}. Keyed by identity rather than by name so that renaming a machine cannot
 * orphan its login — there is nothing to carry over, because nothing moved.
 */
public interface ForPersistingHostCredentials {

    /** Persist {@code credential}, replacing any existing credential for the same machine. */
    void save(HostCredential credential);

    /** The credential held for the machine {@code machineId}, or empty when none is stored. */
    Optional<HostCredential> getByMachine(MachineId machineId);

    /** Remove the credential held for the machine {@code machineId}; a no-op when none exists. */
    void deleteByMachine(MachineId machineId);

    /** Every stored host credential. */
    List<HostCredential> getAll();
}
