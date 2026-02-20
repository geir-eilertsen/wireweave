package com.wireweave.domain.port;

import com.wireweave.domain.DockerService;
import java.util.List;

public interface ForGettingDockerInfo {
    List<DockerService> getServicesWithExposedPorts();
}
