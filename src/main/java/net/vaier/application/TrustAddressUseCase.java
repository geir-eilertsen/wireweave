package net.vaier.application;

/**
 * Trusts one address for good (#329 Slice 3c): it joins the operator's own trusted networks as a
 * single-host CIDR, and its current block is lifted at once.
 *
 * <p><b>Both halves are needed, and neither is sufficient.</b> Persisting alone would leave the address
 * banned right now, because CrowdSec re-reads its whitelist parser only when the container restarts
 * (PRD §6.26). Lifting alone would let the next matching scenario ban it again.
 *
 * <p>And Vaier deliberately does <em>not</em> restart CrowdSec to make the new whitelist entry live:
 * bouncing the engine that guards the edge, to apply a rule about who may pass it, is precisely the
 * operator-lockout risk #329 names as this feature's largest. The honest promise is therefore: unblocked
 * now, permanently trusted from the next restart.
 *
 * @throws IllegalArgumentException if {@code sourceIp} is not a plain IPv4 address
 */
public interface TrustAddressUseCase {

    void trustAddress(String sourceIp);
}
