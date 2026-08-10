package net.fjordomatic.application;

import net.fjordomatic.domain.PublishableService;

import java.util.List;

public interface GetPublishableServicesUseCase {
    List<PublishableService> getPublishableServices();
}
