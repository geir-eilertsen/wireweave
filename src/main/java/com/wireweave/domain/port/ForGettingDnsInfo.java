package com.wireweave.domain.port;

import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.Domain;
import java.util.List;

public interface ForGettingDnsInfo {
    List<DnsRecord> getDnsRecords(Domain domain);
    List<Domain> getDomains();
}
