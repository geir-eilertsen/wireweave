package net.fjordomatic.domain.port;

import net.fjordomatic.domain.LanServer;
import net.fjordomatic.domain.MachineId;

import java.util.List;

/**
 * Driven query port for reading registered LAN servers together with their resolved relay
 * anchor. Mirror of {@link ForPersistingLanServers}; used by other domains' services that need
 * a read-only view of the LAN-server catalogue without coupling to the inbound use case.
 */
public interface ForGettingLanServers {

    List<LanServerView> getAll();

    /**
     * A registered LAN server together with whatever routes to it: a relay peer whose {@code lanCidr}
     * contains the server's {@code lanAddress}, or the Fjord server itself when the address falls inside
     * the Fjord server's own LAN CIDR. Both are null when neither covers it — typically because the relay
     * was deleted or its lanCidr changed.
     *
     * @param relayPeerName what to call the relay ({@code "Fjord server"},
     *                      {@link net.fjordomatic.domain.LanAnchor#FJORD_SERVER_NAME}, for the server LAN)
     * @param relayMachineId the relay peer's identity — what a consumer joins on. Null for the server
     *                       LAN, which is not a peer. Present beside the name because the two answer
     *                       different questions, and joining on the name drew a LAN server at the wrong
     *                       relay's coordinates as soon as two machines shared one.
     */
    record LanServerView(LanServer server, String relayPeerName, MachineId relayMachineId) {

        /** A view with no resolved relay identity — the shape every caller used before it carried one. */
        public LanServerView(LanServer server, String relayPeerName) {
            this(server, relayPeerName, null);
        }
    }
}
