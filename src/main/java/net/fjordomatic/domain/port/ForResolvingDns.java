package net.fjordomatic.domain.port;

import java.util.List;

public interface ForResolvingDns {

    /**
     * The A-record answers for {@code fqdn} as a <em>public</em> resolver sees them — the same view
     * Let's Encrypt has when it runs the HTTP-01 challenge. Empty when the name does not resolve.
     */
    List<String> resolveAddresses(String fqdn);
}
