package net.vaier.application;

import net.vaier.domain.SourceAddress;

import java.util.List;

/**
 * Read the addresses the operator has trusted by hand (#348) — and only those.
 *
 * <p>Deliberately <b>not</b> {@link GetTrustedNetworksUseCase}, which is the whole allowlist: the VPN
 * subnet, the Docker bridge CIDR and every relay peer's LAN CIDR as well. Those structural entries exist so
 * the fleet's bouncer can never block the operator's own traffic, and removing one is the lockout
 * {@code domain.LockoutWarning} exists to warn about. This use case is what the Security view lists and
 * hangs an untrust verb off, so the safety property worth having is that it cannot return a structural
 * network at all — it reads the operator's own decisions, from the store that holds nothing else.
 */
public interface GetTrustedAddressesUseCase {

    List<SourceAddress> getTrustedAddresses();
}
