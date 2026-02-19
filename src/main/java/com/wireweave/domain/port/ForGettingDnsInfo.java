package com.wireweave.domain.port;

import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.DnsZone;
import java.util.List;

public interface ForGettingDnsInfo {
    List<DnsRecord> getDnsRecords(DnsZone dnsZone);
    List<DnsZone> getDnsZones();
}
