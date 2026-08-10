package net.fjordomatic.application;

public interface ResolveVpnPeerIdUseCase {

    /**
     * The id of the peer holding {@code ipAddress} — its immutable WireGuard config directory, not its
     * display name. Falls back to the address itself when no peer bears it.
     */
    String resolvePeerIdByIp(String ipAddress);
}
