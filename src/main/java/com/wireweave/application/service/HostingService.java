package com.wireweave.application.service;

import com.wireweave.application.DiscoverHostedServicesUseCase;
import com.wireweave.domain.HostedService;
import com.wireweave.domain.port.ForGettingReverseProxyRoutes;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class HostingService implements DiscoverHostedServicesUseCase {

    private final ForGettingReverseProxyRoutes forGettingReverseProxyRoutes;

    public HostingService(ForGettingReverseProxyRoutes forGettingReverseProxyRoutes) {
        this.forGettingReverseProxyRoutes = forGettingReverseProxyRoutes;
    }

    @Override
    public List<HostedServiceUco> discoverHostedServices() {
        return forGettingReverseProxyRoutes.getReverseProxyRoutes().stream()
            .map(route -> HostedService.builder()
                .name(route.getService())
                .dnsAddress(route.getDomainName())
                .hostAddress(route.getAddress())
                .hostPort(route.getPort())
                .build())
            .map(this::toUco)
            .toList();
    }

    private HostedServiceUco toUco(HostedService route) {
        return new HostedServiceUco(
            route.getName(),
            route.getDnsAddress(),
            route.getHostAddress(),
            route.getHostPort()
        );
    }
}
