package com.wireweave.rest;

import com.wireweave.application.DiscoverHostedServicesUseCase;
import com.wireweave.application.DiscoverHostedServicesUseCase.HostedServiceUco;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/hosted-services")
public class HostedServiceRestController {

    private final DiscoverHostedServicesUseCase discoverHostedServicesUseCase;

    public HostedServiceRestController(DiscoverHostedServicesUseCase discoverHostedServicesUseCase) {
        this.discoverHostedServicesUseCase = discoverHostedServicesUseCase;
    }

    @GetMapping("/discover")
    public List<HostedServiceUco> getHostedServices() {
        return discoverHostedServicesUseCase.discoverHostedServices();
    }
}
