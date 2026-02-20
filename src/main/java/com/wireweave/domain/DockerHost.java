package com.wireweave.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

@AllArgsConstructor
@ToString
public class DockerHost {

    private final String address;

    @Getter
    @ToString.Exclude
    private final String apiToken;

    public String endpointsUrl() {
        return "http://" + address + ":9000/api/endpoints";
    }
}
