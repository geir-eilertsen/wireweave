package net.fjordomatic.application;

import net.fjordomatic.domain.PeerSnapshot;

public interface NotifyAdminsOfPeerTransitionUseCase {
    void notifyAdmins(PeerSnapshot snapshot);
}
