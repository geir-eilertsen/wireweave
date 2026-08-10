package net.fjordomatic.application;

import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.Server;
import java.util.List;

public interface GetServerInfoUseCase {
    List<DockerService> getServicesWithExposedPorts(Server server);
}
