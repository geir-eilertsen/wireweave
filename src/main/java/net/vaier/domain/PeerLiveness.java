package net.vaier.domain;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which peers are connected right now, as one comparable value. A tick of peer stats carries byte
 * counters that change constantly; this keeps only the fact that decides whether a service on a
 * peer is reachable, so two ticks can be asked whether that fact moved.
 */
public record PeerLiveness(Set<String> connectedPublicKeys) {

    public static PeerLiveness of(List<VpnClient> clients) {
        return new PeerLiveness(clients.stream()
            .filter(VpnClient::isConnected)
            .map(VpnClient::publicKey)
            .collect(Collectors.toUnmodifiableSet()));
    }

    /** True when any peer came up or went away since {@code previous}. */
    public boolean differsFrom(PeerLiveness previous) {
        return !connectedPublicKeys.equals(previous.connectedPublicKeys());
    }
}
