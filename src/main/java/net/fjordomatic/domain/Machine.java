package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForGettingPeerConfigurations.PeerConfiguration;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Unified read projection for every machine Fjord manages — both WireGuard peers
 * (the four {@link MachineType#isVpnPeer() VPN-backed} types) and {@link MachineType#LAN_SERVER}
 * entries that sit on a relay's LAN. WG-only fields ({@code publicKey}, {@code allowedIps},
 * runtime state) are null for {@code LAN_SERVER}; {@code dockerPort} is non-null only for
 * LAN servers with {@code runsDocker=true}.
 */
public record Machine(
    MachineId id,
    String name,
    MachineType type,
    String publicKey,
    String allowedIps,
    String endpointIp,
    String endpointPort,
    String latestHandshake,
    String transferRx,
    String transferTx,
    String lanCidr,
    String lanAddress,
    boolean runsDocker,
    Integer dockerPort,
    DeviceCategory deviceCategory,
    Boolean sshAccessOverride
) {

    /**
     * Whether Fjord offers SSH (the credential control now, the web terminal later) for a machine by
     * default, before any operator override — the smart default seeded from what the machine is. True
     * when the device is not an {@link DeviceCategory#isAppliance() appliance} and it is either
     * {@link DeviceCategory#sshCapableByDefault() SSH-capable by category} or a
     * {@link MachineType#isServerType() server-type} machine. An appliance category vetoes the
     * server-type fallback, so a LAN server that is really a printer stays SSH-off.
     */
    public static boolean defaultSshAccess(DeviceCategory deviceCategory, MachineType type) {
        return !deviceCategory.isAppliance()
            && (deviceCategory.sshCapableByDefault() || type.isServerType());
    }

    /** This machine's SSH-access default from its own category and type. */
    public boolean defaultSshAccess() {
        return defaultSshAccess(deviceCategory, type);
    }

    /**
     * Whether this machine could ever be a <b>relay peer</b> — carry a whole network into the fleet. Only
     * a VPN peer of a {@link MachineType#isServerType() server type} can: a {@link MachineType#LAN_SERVER}
     * is reached <em>through</em> a relay and has no tunnel of its own to route into, and a personal
     * device is not somebody's gateway.
     *
     * <p>It is a machine's own reading of itself, not a rule for a form to re-derive — the Explorer's edit
     * form has long asked the same question as {@code peers.has(id) && SERVER_TYPES.has(type)}, and #333
     * needed to ask it a second time before offering a detected network.
     */
    public boolean canRelayALan() {
        return type != null && type.isVpnPeer() && type.isServerType();
    }

    /**
     * Whether Fjord offers SSH for this machine: the operator's {@link #sshAccessOverride() override}
     * when one is set, otherwise the {@link #defaultSshAccess() smart default}. The override — not the
     * device category — is authoritative; the category only seeds the default.
     */
    public boolean effectiveSshAccess() {
        return sshAccessOverride != null ? sshAccessOverride : defaultSshAccess();
    }

    /**
     * Projects a VPN peer into a {@code Machine}. {@code client} is the peer's live WireGuard
     * runtime, or null when the peer has no current session — every runtime field is then null.
     */
    public static Machine fromPeer(PeerConfiguration peer, VpnClient client) {
        return new Machine(
            peer.machineId(),
            peer.name(),
            peer.peerType(),
            client == null ? null : client.publicKey(),
            client == null ? null : client.allowedIps(),
            client == null ? null : client.endpointIp(),
            client == null ? null : client.endpointPort(),
            client == null ? null : client.latestHandshake(),
            client == null ? null : client.transferRx(),
            client == null ? null : client.transferTx(),
            peer.lanCidr(),
            peer.lanAddress(),
            peer.peerType().isServerType(),
            null,
            peer.effectiveDeviceCategory(),
            peer.sshAccess()
        );
    }

    /**
     * Projects a LAN server into a {@code Machine}. {@code anchorLanCidr} is the CIDR of the
     * relay peer (or Fjord server) that routes to it, or null when no anchor covers it.
     */
    public static Machine fromLanServer(LanServer server, String anchorLanCidr) {
        return new Machine(
            server.machineId(),
            server.name(),
            MachineType.LAN_SERVER,
            null, null, null, null, null, null, null,
            anchorLanCidr,
            server.lanAddress(),
            server.runsDocker(),
            server.dockerPort(),
            server.effectiveDeviceCategory(),
            server.sshAccessOverride()
        );
    }

    /**
     * The Fjord server host itself as a singleton synthetic machine (#311) — the box running the
     * whole stack, which is neither a WireGuard peer nor a LAN server. It carries the canonical
     * {@link LanAnchor#FJORD_SERVER_NAME reserved name}, device category {@link DeviceCategory#SERVER},
     * and defaults SSH-access on (it is a server). {@code sshAccessOverride} is the operator's pinned
     * value from the Fjord config, or null to use the default. Its type reuses {@link MachineType#UBUNTU_SERVER}
     * rather than a dedicated enum value so it never disturbs peer/LAN routing logic — Fjord never
     * generates WireGuard config from a {@code Machine} projection. It {@code runsDocker} — the box is
     * itself the Docker engine hosting the whole compose stack — so the Explorer grows a {@code containers}
     * entry for it; the port is null because Fjord reaches that engine over the local socket, not a TCP port.
     * Every peer/LAN-only field is null.
     */
    public static Machine fjordServer(MachineId id, Boolean sshAccessOverride) {
        return new Machine(
            id, LanAnchor.FJORD_SERVER_NAME, MachineType.UBUNTU_SERVER,
            null, null, null, null, null, null, null,
            null, null, true, null, DeviceCategory.SERVER, sshAccessOverride);
    }

    /**
     * Whether this machine is reachable right now, from already-cached signals only — no fresh probe.
     * The {@link LanAnchor#FJORD_SERVER_NAME Fjord server} is always reachable (Fjord runs on it); a
     * {@link MachineType#LAN_SERVER} is reachable when the cached LAN reachability map reports its
     * address {@link Reachability#OK}; every other machine is a VPN peer, reachable when its tunnel
     * handshake is still fresh. Peer freshness reuses {@link VpnClient#isConnected()} rather than
     * re-deriving the staleness rule here — this machine already carries the peer's runtime fields.
     */
    public boolean isReachable(Map<String, Reachability> lanReachability) {
        if (LanAnchor.FJORD_SERVER_NAME.equals(name)) {
            return true;
        }
        if (type == MachineType.LAN_SERVER) {
            return lanReachability != null && lanReachability.get(lanAddress) == Reachability.OK;
        }
        return new VpnClient(publicKey, allowedIps, endpointIp, endpointPort, latestHandshake,
            transferRx, transferTx).isConnected();
    }


    /**
     * What to call the machine {@code machineId} in something a person reads — an admin email, the
     * recovery sheet — given the name the fleet currently has for it, if any.
     *
     * <p>Records are keyed by identity so a rename cannot orphan them, which means a record can outlive the
     * machine it points at. What to say then is a decision, not a formatting detail: it must be
     * distinguishable from a real name (or an operator reads past it), and it must still carry the id (or an
     * operator who notices cannot act on it). It lives here because it was being answered four different
     * ways — in the recovery sheet, in two admin alerts and in a REST DTO.
     *
     * <p>Not for machine-readable fields. A DTO or an SSE payload wants {@code null} or the raw id, never
     * this prose; those callers deliberately do not come here.
     */
    public static String labelFor(MachineId machineId, Optional<String> name) {
        return name.filter(n -> !n.isBlank()).orElseGet(() ->
            "a machine no longer in this fleet (" + (machineId == null ? "unknown id" : machineId.value()) + ")");
    }
}
