package com.wireweave.application.service;

import com.wireweave.application.GetDnsInfoUseCase;
import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.DnsRecord.DnsRecordType;
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

    @Override
    public List<DnsRecordUco> getDnsRecords(DnsZoneUco dnsZone) {
        return forGettingDnsInfo.getDnsRecords(toDomain(dnsZone))
            .stream()
            .filter(dnsRecord -> dnsRecord.type() == DnsRecordType.CNAME)
            .map(this::toUco)
            .toList();
    }

    private DnsZone toDomain(DnsZoneUco dnsZoneUco) {
        return new DnsZone(dnsZoneUco.name());
    }

    private DnsZoneUco toUco(DnsZone dnsZone) {
        return new DnsZoneUco(dnsZone.name());
    }

    private DnsRecordUco toUco(DnsRecord dnsRecord) {
        return new DnsRecordUco(dnsRecord.name());
    }
}
