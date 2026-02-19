package com.wireweave.domain.port;

public interface KeyPairGenerator {

    KeyPair generate();

    record KeyPair(String privateKey, String publicKey) {}
}
