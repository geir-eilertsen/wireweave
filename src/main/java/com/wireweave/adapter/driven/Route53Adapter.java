package com.wireweave.adapter.driven;

import com.wireweave.domain.DnsRecord;
import com.wireweave.domain.Domain;
import com.wireweave.domain.port.ForGettingDnsRecords;
import java.util.List;

public class Route53Adapter implements ForGettingDnsRecords {

    @Override
    public List<DnsRecord> getDnsRecords(Domain domain) {
        return List.of();
    }
}
