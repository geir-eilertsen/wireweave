package com.wireweave.domain;

import java.util.List;

public record DnsRecord(
        String name,
        String type,
        Long ttl,
        List<String> values
) {
}
