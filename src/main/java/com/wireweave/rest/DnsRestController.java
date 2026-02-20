package com.wireweave.rest;

import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.application.GetDnsInfoUseCase.DnsRecordUco;
import com.wireweave.application.GetDnsInfoUseCase.DnsZoneUco;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping("/zones/{zoneName}/records")
    public List<String> getDnsRecords(@PathVariable String zoneName) {
        return getDnsInfoUseCase.getDnsRecords(new DnsZoneUco(zoneName)).stream().map(DnsRecordUco::name).toList();
    }
}
