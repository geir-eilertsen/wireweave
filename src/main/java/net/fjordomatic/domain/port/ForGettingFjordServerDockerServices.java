package net.fjordomatic.domain.port;

import net.fjordomatic.domain.PublishableService;
import net.fjordomatic.domain.ReverseProxyRoute;

import java.util.List;

/**
 * Driven query port exposing the unpublished Fjord-server services. Mirror of the inbound
 * {@code GetFjordServerDockerServicesUseCase}; used by the publishing service to read which
 * Fjord-server containers are not yet published without coupling to the inbound use case.
 */
public interface ForGettingFjordServerDockerServices {

    List<PublishableService> getUnpublishedFjordServerServices(List<ReverseProxyRoute> existingRoutes);
}
