package net.vaier.application;

public interface DeletePublishedServiceUseCase {

    /**
     * Delete a published service. {@code pathPrefix} is optional; null means the host-only route
     * (the legacy one-service-per-host case). Removing a route takes down the Traefik route and
     * nothing else — the name goes on resolving under the operator's one {@code *.<domain>} record,
     * which is theirs and not Vaier's, so sibling routes on the same FQDN are fully independent
     * (#331).
     */
    void deleteService(String fqdn, String pathPrefix);

    /** Convenience overload for the common host-only case (no pathPrefix). */
    default void deleteService(String fqdn) {
        deleteService(fqdn, null);
    }
}
