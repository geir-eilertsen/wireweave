package net.fjordomatic.application;

import net.fjordomatic.domain.DockerService;

import java.util.List;

public interface DiscoverFjordServerContainersUseCase {

    List<DockerService> discover();
}
