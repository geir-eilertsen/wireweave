package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.fjordomatic.domain.port.ForPersistingLanServers;
import net.fjordomatic.domain.port.ForResolvingFjordServerSshAddress;

import java.util.Optional;

/**
 * Where Fjord opens the SSH connection for a machine — a decision by <b>machine kind</b>, not a lookup:
 * a VPN peer answers at its <b>tunnel IP</b>, a LAN server at its <b>lanAddress</b>, and the Fjord server
 * host at the address it resolves to from inside the container (it is neither a peer nor a LAN server, so
 * its address cannot be read from config at all).
 *
 * <p>It lives in the domain because every consumer must get the same answer: the web terminal, remote
 * commands and the Explorer all reach the same machine at the same place. The stores it needs arrive as
 * driven ports, which the domain calls itself.
 */
public final class SshAddress {

    private SshAddress() {
    }

    /**
     * The SSH host for the machine {@code machineId}. Throws {@link NotFoundException} when no machine has
     * that id — being neither a peer, nor a LAN server, nor the Fjord server, it does not exist.
     *
     * <p>{@code fjordServerId} is the Fjord server's own identity, supplied because it is the one machine
     * whose id lives in the Fjord config rather than in a machine store — it cannot be found by searching.
     * A Fjord that has not been assigned one yet simply does not match, so the host is never reported as the
     * address of a machine that merely failed to be found.
     *
     * <p>There is no peer-wins tie-break any more. It existed because two machines could share a name and a
     * name could not tell them apart; two machines cannot share an id.
     */
    public static String of(MachineId machineId,
                            ForGettingPeerConfigurations peers,
                            ForPersistingLanServers lanServers,
                            ForResolvingFjordServerSshAddress fjordServer,
                            MachineId fjordServerId) {
        if (machineId != null && machineId.isSameAs(fjordServerId)) {
            return fjordServer.resolve();
        }
        Optional<PeerConfiguration> peer = peers.getAllPeerConfigs().stream()
            .filter(p -> p.machineId() != null && p.machineId().isSameAs(machineId))
            .findFirst();
        if (peer.isPresent()) {
            return peer.get().ipAddress();
        }
        return lanServers.getAll().stream()
            .filter(server -> server.machineId() != null && server.machineId().isSameAs(machineId))
            .findFirst()
            .map(LanServer::lanAddress)
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineId));
    }
}
