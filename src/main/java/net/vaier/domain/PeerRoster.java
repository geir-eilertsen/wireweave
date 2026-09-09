package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

/**
 * Every peer Vaier holds a config for, whether or not the running interface knows it right now. A
 * peer the interface has forgotten — a restart that re-read a config without it, a removal that
 * stopped short — is still a machine with a directory, and it has to be listed to be deletable.
 */
public final class PeerRoster {

    private PeerRoster() {}

    public static List<VpnClient> reconcile(List<VpnClient> live, List<PeerConfiguration> configured) {
        List<VpnClient> roster = new ArrayList<>(live);
        for (PeerConfiguration config : configured) {
            String ip = config.ipAddress();
            if (ip == null || ip.isBlank()) continue;
            if (live.stream().anyMatch(client -> client.containsAddress(ip))) continue;
            roster.add(VpnClient.absent(config.publicKey(), ip));
        }
        return roster;
    }
}
