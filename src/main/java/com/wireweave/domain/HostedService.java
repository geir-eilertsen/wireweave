package com.wireweave.domain;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class HostedService {
    private final String name;
    private final String dnsAddress;
    private final String hostAddress;
    private final int hostPort;
}
