package com.wireweave.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@Getter
@ToString
public class ReverseProxyRoute {
    private final String name;
    private final String domainName;
    private final String address;
    private final int port;
    private final String service;
}
