package net.vaier.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Domain rules for working with a peer's WireGuard {@code AllowedIPs} field — a comma-separated
 * CIDR list. Adapters parse the raw string, but the kernel-route delta (what to install / what to
 * remove) is a domain decision. Every route in it is Vaier's to keep: {@code wg-quick up} installs
 * routes only at bring-up, and a peer added or changed with {@code wg set} gets none, so the host
 * route to a phone and the LAN a relay carries are reconciled here alike.
 */
public final class AllowedIps {

    private AllowedIps() {}

    /**
     * Compute which CIDRs need {@code ip route replace} (add) and which need {@code ip route del}
     * (remove) given the previous and new {@code AllowedIPs} values for one peer.
     *
     * <p>Every CIDR in the new set is re-emitted as an add (idempotent via {@code ip route replace})
     * so any drift heals on the next reconcile; only what dropped out is removed.
     */
    public static RouteDelta routeDelta(String oldAllowedIps, String newAllowedIps) {
        List<String> oldCidrs = parseCidrList(oldAllowedIps);
        List<String> newCidrs = parseCidrList(newAllowedIps);
        Set<String> newSet = new HashSet<>(newCidrs);

        List<String> toAdd = newCidrs.stream()
            .distinct()
            .toList();

        List<String> toRemove = oldCidrs.stream()
            .filter(cidr -> !newSet.contains(cidr))
            .distinct()
            .toList();

        return new RouteDelta(toAdd, toRemove);
    }

    /** Parse a comma-separated AllowedIPs value into a de-duplicated list, preserving order. */
    public static List<String> parseCidrList(String allowedIps) {
        if (allowedIps == null || allowedIps.isBlank()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        for (String cidr : allowedIps.split(",")) {
            String trimmed = cidr.trim();
            if (!trimmed.isEmpty()) seen.add(trimmed);
        }
        return new ArrayList<>(seen);
    }

    /** The CIDRs to install via {@code ip route replace} and to drop via {@code ip route del}. */
    public record RouteDelta(List<String> toAdd, List<String> toRemove) {}
}
