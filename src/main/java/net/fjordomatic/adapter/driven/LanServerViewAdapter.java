package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.LanAnchor;
import net.fjordomatic.domain.port.ForGettingLanServers;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.fjordomatic.domain.port.ForPersistingLanServers;
import net.fjordomatic.domain.port.ForResolvingServerLanCidr;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Driven adapter that reads the registered LAN servers and pairs each with the name of whatever
 * routes to it — a relay peer whose {@code lanCidr} contains the server's address, or the Fjord
 * server itself. The read used to live on {@code LanServerService}, but a {@code *Service} must not
 * implement a driven ({@code For*}) port; the view assembly is a pure read over three driven ports,
 * so it belongs in an adapter that every consumer (including {@code LanServerService}'s own
 * {@code GetLanServersUseCase}) reaches through {@link ForGettingLanServers}.
 */
@Component
public class LanServerViewAdapter implements ForGettingLanServers {

    private final ForPersistingLanServers forPersistingLanServers;
    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForResolvingServerLanCidr forResolvingServerLanCidr;

    public LanServerViewAdapter(ForPersistingLanServers forPersistingLanServers,
                                ForGettingPeerConfigurations forGettingPeerConfigurations,
                                ForResolvingServerLanCidr forResolvingServerLanCidr) {
        this.forPersistingLanServers = forPersistingLanServers;
        this.forGettingPeerConfigurations = forGettingPeerConfigurations;
        this.forResolvingServerLanCidr = forResolvingServerLanCidr;
    }

    @Override
    public List<LanServerView> getAll() {
        List<PeerConfiguration> peers = forGettingPeerConfigurations.getAllPeerConfigs();
        String serverLanCidr = forResolvingServerLanCidr.resolve().orElse(null);
        return forPersistingLanServers.getAll().stream()
            .map(s -> {
                var anchor = LanAnchor.resolve(s.lanAddress(), peers, serverLanCidr);
                return new LanServerView(s,
                    anchor.map(LanAnchor::name).orElse(null),
                    anchor.flatMap(LanAnchor::relayMachineId).orElse(null));
            })
            .toList();
    }
}
