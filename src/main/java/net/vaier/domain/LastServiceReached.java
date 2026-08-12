package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * The service a machine most recently reached through Vaier's forward-auth check, and when. Everything
 * Vaier gates counts — a {@code ReverseProxyRoute published service}, the console, the Explorer — because
 * every one of them goes through the same check, and "what did the phone last open" is a fair question
 * about any of them.
 *
 * <p><b>Only the tunnel names a device</b>, and that decision lives in {@link TunnelCaller}. Every other
 * address — a carrier IP, a LAN address, the public internet — identifies a person and not a device, so
 * attributing one to whichever peer last connected from it would draw one household's browsing on
 * another's machine. Vaier would rather know nothing than say something it cannot support, which is why
 * {@link #reachedOverTheTunnel} answers empty rather than falling back to a peer's endpoint address.
 */
public record LastServiceReached(MachineId machineId, String host, Instant at) {

    public LastServiceReached {
        if (machineId == null) {
            throw new IllegalArgumentException("A reached service belongs to a machine");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("A reached service has a host");
        }
        if (at == null) {
            throw new IllegalArgumentException("A reached service knows when it was reached");
        }
    }

    /**
     * What this access says about which machine reached which service — or nothing at all, which is the
     * ordinary answer for the whole public internet.
     *
     * <p>Who the caller is, is {@link TunnelCaller}'s single answer — position reporting guards itself
     * with the same one. All that is left here is naming the service they reached.
     */
    public static Optional<LastServiceReached> reachedOverTheTunnel(
            String callerIp, String vpnSubnet, String host, Instant at,
            ForGettingPeerConfigurations peers) {
        String name = hostName(host);
        if (name == null || at == null) {
            return Optional.empty();
        }
        return TunnelCaller.machineFor(callerIp, vpnSubnet, peers)
            .map(machineId -> new LastServiceReached(machineId, name, at));
    }

    /** Whether this is that machine's reach. */
    public boolean isFor(MachineId other) {
        return machineId.isSameAs(other);
    }

    /** Whether this reach supersedes {@code other} — a null {@code other} is nothing to supersede. */
    public boolean isNewerThan(LastServiceReached other) {
        return other == null || at.isAfter(other.at());
    }

    /** {@code X-Forwarded-Host} carries the port when there is one, and any case the caller typed. */
    private static String hostName(String host) {
        if (host == null || host.isBlank()) return null;
        String trimmed = host.trim();
        int colon = trimmed.indexOf(':');
        String name = (colon < 0 ? trimmed : trimmed.substring(0, colon)).toLowerCase(Locale.ROOT);
        return name.isBlank() ? null : name;
    }
}
