package com.wireweave.application;

import java.util.List;

public interface GetDnsInfoUseCase {

    List<DnsZoneUco> getDnsZones();

    record DnsZoneUco(
        String name
    ) {}
}
