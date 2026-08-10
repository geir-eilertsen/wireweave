package net.fjordomatic.domain.port;

import net.fjordomatic.domain.DockerService;

import java.util.List;

/**
 * Driven query port exposing the cached Fjord-server container scrape. Mirror of the inbound
 * {@code DiscoverFjordServerContainersUseCase}; used by other domains' services (e.g. publishing)
 * that need a read-only view of discovered Fjord-server containers without coupling to the
 * inbound use case.
 */
public interface ForDiscoveringFjordServerContainers {

    List<DockerService> discover();
}
