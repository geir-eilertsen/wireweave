package net.fjordomatic.domain.port;

import java.util.List;
import java.util.Optional;

/**
 * Driven port that sweeps a LAN CIDR and reports the hosts that respond. The driven side owns the
 * probe mechanism (an ICMP/TCP sweep run from the Fjord WireGuard container, which already routes
 * to every relay peer's LAN) and the parsing of its output. Callers get back only responsive
 * hosts — an empty list when nothing answered or the CIDR is unreachable.
 *
 * <p>The scan is intrusive, so it runs only when the application explicitly asks for it — never on
 * a timer and never as a side effect of reading something.
 */
public interface ForScanningLan {

    /** The hosts in {@code cidr} that answered the probe. */
    List<ScannedHost> scan(String cidr);

    /**
     * Probe a <em>single</em> address — the same TCP port sweep aimed at one host rather than a whole
     * CIDR. Used by the manual "add a LAN server by address" helper, which inspects the one host the
     * operator already named. Empty when the host answered nothing (or was unreachable over the tunnel);
     * a host that answered on any port — or only to a ping — is returned with whatever ports it opened.
     */
    Optional<ScannedHost> scanHost(String ipAddress);

    /**
     * A single responsive host.
     *
     * @param ipAddress the host's LAN address
     * @param openPorts the probed service ports it answered on (may be empty for a ping-only hit)
     * @param hostname  a resolved hostname, or {@code null} when none was discoverable
     */
    record ScannedHost(String ipAddress, List<Integer> openPorts, String hostname) {}
}
