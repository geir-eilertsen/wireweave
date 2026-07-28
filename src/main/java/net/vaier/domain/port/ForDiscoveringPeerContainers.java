package net.vaier.domain.port;

import net.vaier.domain.DockerService;

import java.util.List;

/**
 * Driven query port exposing the cached server-peer container scrape. Mirror of the inbound
 * {@code DiscoverPeerContainersUseCase}; used by other domains' services (e.g. publishing) that
 * need a read-only view of discovered peer containers without coupling to the inbound use case.
 */
public interface ForDiscoveringPeerContainers {

    List<PeerContainers> discoverAll();

    /**
     * @param machineId the identity of the machine these containers are on, so a consumer can file them
     *                  against the machine rather than against whatever it is currently called. Null only
     *                  for a live WireGuard peer with no stored config — it is in no registry, so it has
     *                  no identity to report and inventing one would join it to nothing.
     * @param peerId    the peer's WireGuard config directory. Never its display name — it was called
     *                  {@code peerName} and held this same value, which is the sort of lie a later reader
     *                  compares against a name.
     */
    record PeerContainers(
            String machineId,
            String peerId,
            String vpnIp,
            String status,
            List<DockerService> containers,
            boolean wireguardOutdated,
            String wireguardExpectedImage
    ) {}
}
