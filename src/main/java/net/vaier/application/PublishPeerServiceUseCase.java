package net.vaier.application;

import java.util.List;

public interface PublishPeerServiceUseCase {

    /**
     * Publish a service. {@code pathPrefix} is optional; when null the route catches everything on
     * the host. When non-null (e.g. {@code "/auth"}) the route only matches that path scope,
     * letting multiple services coexist on one host. Publishing writes the Traefik route and nothing
     * else — the operator's single {@code *.<domain>} record already resolves the name (#331).
     */
    void publishService(String address, int port, String subdomain, boolean requiresAuth,
                        String rootRedirectPath, boolean directUrlDisabled, String pathPrefix);

    /** Convenience overload for the common host-only case. */
    default void publishService(String address, int port, String subdomain, boolean requiresAuth,
                                String rootRedirectPath, boolean directUrlDisabled) {
        publishService(address, port, subdomain, requiresAuth, rootRedirectPath, directUrlDisabled, null);
    }

    PublishStatus getPublishStatus(String subdomain);

    List<PendingPublication> getPendingPublications();

    /** Whether Traefik has picked the route up. Publishing has exactly one phase now (#331). */
    record PublishStatus(boolean traefikActive) {}

    record PendingPublication(String subdomain, boolean requiresAuth) {}
}
