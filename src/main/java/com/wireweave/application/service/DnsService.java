package com.wireweave.application.service;

import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.domain.DnsZone;
import com.wireweave.domain.port.ForGettingDnsInfo;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DnsService implements GetDnsInfoUseCase {

    private final ForGettingDnsInfo forGettingDnsInfo;

    public DnsService(ForGettingDnsInfo forGettingDnsInfo) {
        this.forGettingDnsInfo = forGettingDnsInfo;
    }

    @Override
    public List<DnsZoneUco> getDnsZones() {
        return forGettingDnsInfo.getDnsZones().stream()
            .map(this::toUco).toList();
    }

    private DnsZoneUco toUco(DnsZone dnsZone) {
        return new DnsZoneUco(dnsZone.name());
    }
}
