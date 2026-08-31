package net.vaier.domain.port;

import net.vaier.domain.FleetCredential;

import java.util.List;
import java.util.Optional;

/**
 * Driven port for the fleet-credential store — the same vault that holds host credentials, keyed by the
 * credential's own {@code name}.
 *
 * <p>Keyed by name rather than by an identity of its own because, unlike a machine, a fleet credential
 * <em>is</em> its name: the operator picks it, it never drifts under them, and it is what a URL path
 * segment addresses. Renaming one is creating a different credential.
 */
public interface ForPersistingFleetCredentials {

    /** Persist {@code credential}, replacing any existing credential of the same name. */
    void save(FleetCredential credential);

    /** The credential stored under {@code name}, or empty when none is. */
    Optional<FleetCredential> getByName(String name);

    /** Remove the credential stored under {@code name}; a no-op when none exists. */
    void deleteByName(String name);

    /** Every stored fleet credential. */
    List<FleetCredential> getAll();
}
