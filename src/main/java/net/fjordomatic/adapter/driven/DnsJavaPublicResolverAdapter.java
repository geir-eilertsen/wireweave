package net.fjordomatic.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.port.ForResolvingDns;
import org.springframework.stereotype.Component;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Slf4j
public class DnsJavaPublicResolverAdapter implements ForResolvingDns {

    @FunctionalInterface
    interface AddressQueryAtResolver {
        List<String> addressesOf(String fqdn, String resolverHost);
    }

    /**
     * The public resolvers a wildcard probe is asked of, in order. These are the internet's view of the
     * name — the same one Let's Encrypt takes when it runs the HTTP-01 challenge — deliberately not the
     * zone's own authoritative nameservers and not this container's resolver.
     */
    private static final List<String> PUBLIC_RESOLVERS = List.of("1.1.1.1", "8.8.8.8");

    AddressQueryAtResolver addressQueryAtResolver = new DnsJavaAddressQueryAtResolver();

    @Override
    public List<String> resolveAddresses(String fqdn) {
        for (String resolverHost : PUBLIC_RESOLVERS) {
            List<String> addresses = addressQueryAtResolver.addressesOf(fqdn, resolverHost);
            if (!addresses.isEmpty()) {
                log.debug("{} resolves to {} at public resolver {}", fqdn, addresses, resolverHost);
                return addresses;
            }
        }
        log.debug("{} does not resolve at any public resolver", fqdn);
        return Collections.emptyList();
    }

    static class DnsJavaAddressQueryAtResolver implements AddressQueryAtResolver {
        @Override
        public List<String> addressesOf(String fqdn, String resolverHost) {
            try {
                SimpleResolver resolver = new SimpleResolver(resolverHost);
                resolver.setTimeout(Duration.ofSeconds(5));
                Lookup lookup = new Lookup(Name.fromString(fqdn, Name.root), Type.A);
                lookup.setResolver(resolver);
                lookup.setCache(new Cache());
                Record[] records = lookup.run();
                if (records == null) return Collections.emptyList();
                List<String> addresses = new ArrayList<>();
                for (Record r : records) {
                    if (r instanceof ARecord a) {
                        addresses.add(a.getAddress().getHostAddress());
                    }
                }
                return addresses;
            } catch (Exception e) {
                log.debug("Failed to resolve {} at public resolver {}: {}", fqdn, resolverHost, e.getMessage());
                return Collections.emptyList();
            }
        }
    }
}
