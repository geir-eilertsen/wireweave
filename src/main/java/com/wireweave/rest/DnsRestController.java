package com.wireweave.rest;

import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.application.GetDnsInfoUseCase.DnsZoneUco;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/dns")
public class DnsRestController {

    private final GetDnsInfoUseCase getDnsInfoUseCase;

    public DnsRestController(GetDnsInfoUseCase getDnsInfoUseCase) {
        this.getDnsInfoUseCase = getDnsInfoUseCase;
    }

    @GetMapping("/zones")
    public List<String> getDnsZones() {
        return getDnsInfoUseCase.getDnsZones().stream().map(DnsZoneUco::name).toList();
    }
}
