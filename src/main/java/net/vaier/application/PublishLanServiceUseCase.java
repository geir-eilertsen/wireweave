package net.vaier.application;

import net.vaier.domain.MachineId;

public interface PublishLanServiceUseCase {

    /**
     * Publish a Traefik route for a LAN service reachable via a relay peer (no Docker container required).
     * Resolves {@code machineId} to a registered {@link net.vaier.domain.LanServer} and uses its
     * {@code lanAddress} as the backend host; throws {@link IllegalArgumentException} when no LAN server
     * has that identity. The resolved host must fall inside some relay peer's {@code lanCidr} (or the
     * Vaier server's own LAN CIDR), otherwise also throws.
     *
     * <p>By identity rather than by name: publishing writes a DNS record and a route pointing at whatever
     * address the lookup returns, and a name that matched the wrong machine would put a service on the
     * internet in front of a host nobody chose.
     *
     * <p>{@code rootRedirectPath} may be null; when non-null, Traefik will redirect requests to the
     * service root (`/`) to {@code https://<fqdn><rootRedirectPath>}. {@code pathPrefix} is optional;
     * when set, the route only matches that path on the host so multiple LAN services can share
     * a single FQDN.
     */
    void publishLanService(String subdomain, MachineId machineId, int port, String protocol,
                           boolean requiresAuth, boolean directUrlDisabled, String rootRedirectPath,
                           String pathPrefix);
}
