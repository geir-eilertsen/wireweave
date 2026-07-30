package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The operator's own networks — the VPN subnet, the Docker bridge CIDR, and every relay peer's
 * {@code lanCidr} — which must never be blocked by the fleet's threat-detection bouncer (#329).
 * Rendered by {@code CrowdSecWhitelistFileAdapter} into CrowdSec's whitelist-parser file, so a
 * false-positive block decision can never reach the operator's own traffic in the first place —
 * the break-glass unban runbook exists for genuine false positives elsewhere, not for this.
 */
public final class TrustedNetworks {

    private final List<String> cidrStrings;
    private final List<Cidr> cidrs;

    private TrustedNetworks(List<String> cidrStrings) {
        this.cidrStrings = List.copyOf(cidrStrings);
        this.cidrs = this.cidrStrings.stream().map(Cidr::parse).toList();
    }

    /**
     * @param vpnSubnet        the WireGuard VPN subnet (e.g. {@code 10.13.13.0/24}); required.
     * @param dockerBridgeCidr the {@code vaier-network} Docker bridge CIDR (e.g. {@code 172.20.0.0/16});
     *                         required.
     * @param relayLanCidrs    every relay peer's {@code lanCidr}, blank/null entries already
     *                         filtered out; may be null or empty when no relay is configured yet.
     */
    public static TrustedNetworks of(String vpnSubnet, String dockerBridgeCidr, List<String> relayLanCidrs) {
        if (vpnSubnet == null || vpnSubnet.isBlank()) {
            throw new IllegalArgumentException("vpnSubnet must not be blank");
        }
        if (dockerBridgeCidr == null || dockerBridgeCidr.isBlank()) {
            throw new IllegalArgumentException("dockerBridgeCidr must not be blank");
        }
        List<String> all = new ArrayList<>();
        all.add(vpnSubnet.trim());
        all.add(dockerBridgeCidr.trim());
        if (relayLanCidrs != null) {
            for (String cidr : relayLanCidrs) {
                if (cidr != null && !cidr.isBlank()) {
                    all.add(cidr.trim());
                }
            }
        }
        return new TrustedNetworks(all);
    }

    /** Every CIDR in this allowlist, in the order VPN subnet, Docker bridge, then relay LANs. */
    public List<String> allCidrs() {
        return cidrStrings;
    }

    /** Whether {@code ip} falls inside the VPN subnet, the Docker bridge, or any relay LAN. */
    public boolean contains(String ip) {
        return cidrs.stream().anyMatch(cidr -> cidr.contains(ip));
    }
}
