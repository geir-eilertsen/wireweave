package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.DnsRecordType;

public interface ForResolvingPublicHost {

    Optional<PublicHost> resolve();

    /**
     * Returns the server's public IP if known, regardless of how `resolve()` chose to represent
     * the public host. On EC2 this returns the value from IMDS `public-ipv4`, which differs from
     * resolving the EC2 public hostname inside the VPC (split-horizon DNS would yield the private
     * IP). Used for IP geolocation, where a CNAME or split-horizon address won't do.
     */
    default Optional<String> resolvePublicIp() {
        return Optional.empty();
    }

    /**
     * How this server's public address is expressed: an IP is an {@link DnsRecordType#A}, a hostname a
     * {@link DnsRecordType#CNAME}. The distinction survives Vaier getting out of the DNS business
     * (#331) because a CNAME still has to be resolved before it can be geolocated.
     */
    record PublicHost(String value, DnsRecordType type) {}
}
