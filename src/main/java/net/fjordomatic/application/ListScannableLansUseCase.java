package net.fjordomatic.application;

import java.util.List;

/**
 * Lists the LANs Fjord can sweep for unregistered machines (issue #246): each relay peer that
 * routes a LAN, plus the Fjord server's own LAN when its CIDR is known. The "pick a LAN first"
 * picker offers exactly these, so an operator scans one LAN at a time rather than every LAN at once.
 */
public interface ListScannableLansUseCase {

    List<ScannableLan> scannableLans();

    /**
     * A LAN the operator can target for a scan.
     *
     * @param anchor the stable routing key to scan and to filter discovered hosts by (a relay
     *               peer's id, or {@code LanAnchor.FJORD_SERVER_NAME} for the server LAN)
     * @param name   the display label shown as "via {@code <name>}" (the peer's name, or "Fjord server")
     * @param cidr   the LAN CIDR that will be swept
     */
    record ScannableLan(String anchor, String name, String cidr) {}
}
