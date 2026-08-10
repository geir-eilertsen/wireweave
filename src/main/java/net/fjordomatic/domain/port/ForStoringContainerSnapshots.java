package net.fjordomatic.domain.port;

import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.ScopedImage;
import net.fjordomatic.domain.UpdateAvailability;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers.PeerContainers;

import java.util.List;
import java.util.Map;

/**
 * Driven port for the write/owner side of the cached container snapshots. The peer- and
 * Fjord-server container scrapes and the last image-update sweep's verdicts used to live as
 * {@code volatile} fields on {@code ContainerService}, exposed through driven read ports the service
 * itself implemented — which a {@code *Service} must not do. The state moved to a store adapter; the
 * scrape/sweep use cases (which stay in {@code ContainerService}) write it and read it raw through
 * this port, while consumers read the decorated views through {@link ForDiscoveringFjordServerContainers},
 * {@link ForDiscoveringPeerContainers} and {@link ForGettingFjordServerDockerServices}.
 */
public interface ForStoringContainerSnapshots {

    /** Replace the cached server-peer container scrape. */
    void storePeerContainers(List<PeerContainers> peers);

    /** Replace the cached Fjord-server container scrape. */
    void storeFjordServerContainers(List<DockerService> containers);

    /** Replace the last image-update sweep's verdicts (image → verdict). */
    void storeImageUpdateVerdicts(Map<ScopedImage, UpdateAvailability> verdicts);

    /**
     * Forget the remembered verdict for one {@link ScopedImage}, so it reads
     * {@link UpdateAvailability#UNKNOWN} again — what every image no sweep has judged reads.
     *
     * <p>Deliberately narrow. The update path needs to retire exactly one container's verdict, and doing
     * that by rewriting the whole map would race the sweep: a sweep landing between an update's pull and
     * its settle would be clobbered by a map assembled before it ran. One key, left to the store to remove.
     */
    void forgetImageUpdateVerdict(ScopedImage image);

    /** The raw cached server-peer scrape (undecorated), for feeding a sweep. */
    List<PeerContainers> peerContainers();

    /** The raw cached Fjord-server scrape (undecorated), for feeding a sweep. */
    List<DockerService> fjordServerContainers();

    /** The last sweep's verdicts, for comparing a fresh sweep against. */
    Map<ScopedImage, UpdateAvailability> imageUpdateVerdicts();
}
