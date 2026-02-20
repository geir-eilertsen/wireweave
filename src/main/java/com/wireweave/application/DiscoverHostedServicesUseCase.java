package com.wireweave.application;

import java.util.List;

public interface DiscoverHostedServicesUseCase {

    List<HostedServiceUco> discoverHostedServices();

    record HostedServiceUco(
        String name,
        String dnsAddress,
        String hostAddress,
        int hostPort
    ){}
}
