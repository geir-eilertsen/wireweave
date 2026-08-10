package net.fjordomatic.domain;

import lombok.Getter;

/**
 * A DNS record type. Fjord no longer writes DNS — the operator's single {@code *.<domain>} A record is
 * the whole story — but it still has to say how this server's public address is <em>expressed</em>: an
 * IP is an {@code A}, a hostname is a {@code CNAME}, and the two are not interchangeable when a
 * location has to be geolocated.
 */
@Getter
public enum DnsRecordType {
    A("A"),
    CNAME("CNAME");

    private final String value;

    DnsRecordType(String value) {
        this.value = value;
    }
}
