package net.fjordomatic.rest;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.GetPeerConfigUseCase;
import net.fjordomatic.application.GetVpnClientsUseCase;
import net.fjordomatic.application.NotifyAdminsOfPeerTransitionUseCase;
import net.fjordomatic.application.ResolveVpnPeerIdUseCase;
import net.fjordomatic.domain.PeerConnectivityTracker;
import net.fjordomatic.domain.PeerSnapshot;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.VpnClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class PeerConnectivityWatcher {

    private final GetVpnClientsUseCase vpnClients;
    private final ResolveVpnPeerIdUseCase peerIdResolver;
    private final GetPeerConfigUseCase peerConfigs;
    private final NotifyAdminsOfPeerTransitionUseCase notifier;
    private final PeerConnectivityTracker tracker = new PeerConnectivityTracker();

    public PeerConnectivityWatcher(GetVpnClientsUseCase vpnClients,
                                   ResolveVpnPeerIdUseCase peerIdResolver,
                                   GetPeerConfigUseCase peerConfigs,
                                   NotifyAdminsOfPeerTransitionUseCase notifier) {
        this.vpnClients = vpnClients;
        this.peerIdResolver = peerIdResolver;
        this.peerConfigs = peerConfigs;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelay = 30000)
    public void checkConnectivity() {
        try {
            List<PeerSnapshot> serverSnapshots = vpnClients.getClients().stream()
                    .map(this::toSnapshot)
                    .filter(s -> s != null && s.peerType().isVpnPeer() && s.peerType().isServerType())
                    .toList();
            for (PeerSnapshot transition : tracker.update(serverSnapshots)) {
                notifier.notifyAdmins(transition);
            }
        } catch (Exception e) {
            log.debug("Peer connectivity check failed: {}", e.getMessage());
        }
    }

    private PeerSnapshot toSnapshot(VpnClient client) {
        if (client.allowedIps() == null || client.allowedIps().isBlank()) return null;
        String peerIp = client.vpnIp();
        if (peerIp.isEmpty()) return null;

        String name = peerIdResolver.resolvePeerIdByIp(peerIp);
        Optional<GetPeerConfigUseCase.PeerConfigResult> cfg = peerConfigs.getPeerConfigByIp(peerIp);
        MachineType type = cfg.map(GetPeerConfigUseCase.PeerConfigResult::peerType).orElse(MachineType.defaultType());
        String lanAddress = cfg.map(GetPeerConfigUseCase.PeerConfigResult::lanAddress).orElse(null);

        return new PeerSnapshot(name, type, client.isConnected(), client.latestHandshakeEpoch(), lanAddress);
    }
}
