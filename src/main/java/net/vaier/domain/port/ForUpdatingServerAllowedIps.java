package net.vaier.domain.port;

public interface ForUpdatingServerAllowedIps {

    /**
     * Updates the server-side {@code [Peer]} {@code AllowedIPs} entry for the peer at
     * the given VPN IP, then persists the change so it survives container restarts.
     * Hot — does not disconnect existing tunnels for unrelated peers.
     *
     * @param peerIpAddress the peer's VPN IP (e.g. {@code 10.13.13.6})
     * @param allowedIps    the new comma-separated AllowedIPs value
     *                      (e.g. {@code 10.13.13.6/32, 192.168.3.0/24})
     */
    void setPeerAllowedIps(String peerIpAddress, String allowedIps);

    /**
     * Installs the kernel routes a freshly added peer's {@code AllowedIPs} need, so its traffic is
     * reachable without bringing the interface down and up again — which is what used to happen,
     * and dropped every tunnel for every peer each time one was added.
     *
     * @param allowedIps the comma-separated AllowedIPs the peer was registered with
     */
    void installRoutes(String allowedIps);
}
