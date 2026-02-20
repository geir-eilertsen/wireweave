package com.wireweave.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@AllArgsConstructor
@Getter
@ToString
public class WireguardConfig {
    private final WireguardInterface interfaceConfig;
    private final List<WireguardPeer> peers;

    @AllArgsConstructor
    @Getter
    @ToString
    public static class WireguardInterface {
        private final String address;
        private final Integer listenPort;
        private final String privateKeyPath;
        private final List<String> postUpCommands;
        private final List<String> postDownCommands;
    }

    @AllArgsConstructor
    @Getter
    @ToString
    public static class WireguardPeer {
        private final String name;
        private final String publicKey;
        private final String allowedIPs;
        private final String endpoint;
        private final Integer persistentKeepalive;
    }
}
