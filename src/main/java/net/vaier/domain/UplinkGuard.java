package net.vaier.domain;

import java.util.List;

/**
 * <b>Never route a host's own network into the tunnel.</b> If a CIDR sent through WireGuard on some host
 * contains the address that host is reached on — the address of its default-route interface — that host
 * blackholes its own uplink the moment the route is installed. It is not a theoretical failure: on
 * 2026-07-23 a setup script pasted into the wrong terminal routed the Vaier staging server's own subnet
 * into a tunnel that could never come up, and severed the box from its default gateway.
 *
 * <p>This class is the <b>single</b> statement of that rule, in both forms it has to take:
 * <ul>
 *   <li>{@link #wouldBlackhole(String, String)} — the Java predicate, asked before Vaier ever
 *       <em>offers</em> a network to route (#333);</li>
 *   <li>{@link #shellRefusal(List)} and {@link #shellHelpers()} — the same rule as bash, carried by every
 *       generated {@link SetupScriptGuard setup script} so it is also enforced on a host Vaier is not
 *       talking to at the time.</li>
 * </ul>
 *
 * <p>They live together because the alternative is two copies of a rule whose failure mode is cutting a
 * machine off the network. Until #333 only the bash existed, so there was nothing for the Java side to
 * reuse and every new consumer would have restated it.
 *
 * <p><b>Which host's uplink.</b> The address to judge against is always the address of the host that will
 * <em>install the route</em>, never the host that owns the network. A relay peer's own LAN is not routed
 * into the relay's tunnel — the relay <em>is</em> the way into that LAN — so the relay is never at risk
 * from its own CIDR. The host at risk is the one adding {@code ip route <cidr> dev wg0}: the Vaier server
 * (see {@code VpnService.syncLanRoutes}), or a machine running a setup script that tunnels the CIDR.
 */
public final class UplinkGuard {

    private UplinkGuard() {
    }

    /**
     * Whether routing {@code cidr} into the tunnel, on a host reached at {@code uplinkAddress}, would cut
     * that host off its own network.
     *
     * <p>Unknown is not danger: a null or unreadable address, or a CIDR that is not one, returns
     * {@code false}. A guard that refuses whenever it cannot tell is a guard that quietly disables the
     * feature it guards — the failure this codebase has already been bitten by once, with the disk alert
     * that could never fire.
     */
    public static boolean wouldBlackhole(String cidr, String uplinkAddress) {
        if (cidr == null || cidr.isBlank() || !Cidr.isIpv4(uplinkAddress)) {
            return false;
        }
        try {
            return Cidr.parse(cidr).contains(uplinkAddress);
        } catch (IllegalArgumentException e) {
            // Not a CIDR at all — it proves nothing about anyone's uplink.
            return false;
        }
    }

    /**
     * The rule as bash, for the host a setup script is about to reconfigure: find the default-route
     * interface, read the address on it, and refuse if any CIDR the script tunnels contains that address.
     * Emitted inside the setup-script guard's {@code VAIER_FORCE} block, so it is indented to match and
     * calls {@code vaier_refuse}; it needs {@link #shellHelpers()} to have been emitted first.
     */
    public static String shellRefusal(List<String> tunneledCidrs) {
        StringBuilder sb = new StringBuilder();
        sb.append("    vaier_def_if=\"$(ip route show default 2>/dev/null | awk '{print $5; exit}')\"\n");
        sb.append("    if [ -n \"$vaier_def_if\" ]; then\n");
        sb.append("        vaier_def_ip=\"$(ip -4 -o addr show dev \"$vaier_def_if\" 2>/dev/null \\\n");
        sb.append("            | awk '{print $4}' | cut -d/ -f1 | head -1)\"\n");
        sb.append("        for vaier_cidr in");
        for (String cidr : tunneledCidrs) sb.append(" ").append(shellQuote(cidr));
        sb.append("; do\n");
        sb.append("            if [ -n \"$vaier_def_ip\" ] && vaier_in_cidr \"$vaier_def_ip\" \"$vaier_cidr\"; then\n");
        sb.append("                vaier_refuse \"this host ($vaier_def_ip on $vaier_def_if) is inside $vaier_cidr,");
        sb.append(" which this script routes into the VPN — it would cut the host off its own network.\"\n");
        sb.append("            fi\n");
        sb.append("        done\n");
        sb.append("    fi\n");
        return sb.toString();
    }

    /**
     * Shell helpers for IPv4-in-CIDR containment, kept separate so they can be exercised directly.
     * Pure bash arithmetic — no python, no ipcalc, nothing a minimal host might lack. This is
     * {@link Cidr#contains(String)}, expressed for a machine Vaier is not connected to.
     */
    public static String shellHelpers() {
        return """
            vaier_ip_to_int() {
                local IFS=.
                local -a vaier_octets
                read -r -a vaier_octets <<< "$1"
                echo $(( (${vaier_octets[0]} << 24) + (${vaier_octets[1]} << 16) \\
                    + (${vaier_octets[2]} << 8) + ${vaier_octets[3]} ))
            }
            vaier_in_cidr() {
                local vaier_ip="$1" vaier_cidr_arg="$2" vaier_net vaier_bits vaier_mask
                vaier_net="${vaier_cidr_arg%/*}"
                vaier_bits="${vaier_cidr_arg#*/}"
                if [ "$vaier_bits" = "$vaier_cidr_arg" ]; then vaier_bits=32; fi
                vaier_mask=$(( 0xFFFFFFFF ^ ((1 << (32 - vaier_bits)) - 1) ))
                [ $(( $(vaier_ip_to_int "$vaier_ip") & vaier_mask )) \\
                    -eq $(( $(vaier_ip_to_int "$vaier_net") & vaier_mask )) ]
            }
            """;
    }

    /**
     * Renders a CIDR as a single-quoted shell literal — the same treatment the setup-script guard gives
     * every operator-typed value that reaches a generated script as data.
     */
    private static String shellQuote(String raw) {
        return "'" + (raw == null ? "" : raw).replace("'", "'\\''") + "'";
    }
}
