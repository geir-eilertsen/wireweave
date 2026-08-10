package net.fjordomatic.application;

import net.fjordomatic.domain.PublishableService;
import net.fjordomatic.domain.ReverseProxyRoute;

import java.util.List;

public interface GetFjordServerDockerServicesUseCase {

    List<PublishableService> getUnpublishedFjordServerServices(List<ReverseProxyRoute> existingRoutes);
}
