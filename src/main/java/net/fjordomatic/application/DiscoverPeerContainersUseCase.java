package net.fjordomatic.application;

import net.fjordomatic.domain.port.ForDiscoveringPeerContainers.PeerContainers;

import java.util.List;

public interface DiscoverPeerContainersUseCase {

    List<PeerContainers> discoverAll();
}
