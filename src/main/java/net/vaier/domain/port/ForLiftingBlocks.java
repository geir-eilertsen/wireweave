package net.vaier.domain.port;

import net.vaier.domain.SourceAddress;

/**
 * Lifts the block CrowdSec placed on one source address — the operator's "that was me, let me back in"
 * (#329 Slice 3c).
 *
 * <p><b>Named for what it does, deliberately.</b> #329 originally called this port
 * {@code ForBlockingAddresses}. Vaier never <em>adds</em> a ban: CrowdSec's own scenarios decide who is
 * blocked, and nothing in Vaier can or should ban an address by hand. A port called "for blocking
 * addresses" would therefore have been a lie about the fleet's threat model — it would read as though
 * Vaier held the block button. Only one direction of the decision is Vaier's to take, and this is it.
 *
 * <p>Unlike {@link ForDetectingIntrusions}, whose contract is that every failure reads as "no active
 * decisions", a failure here must <b>not</b> be swallowed: an operator is standing in front of the screen
 * waiting to learn whether the address is back in. Implementations throw
 * {@link net.vaier.domain.BlockNotLiftedException} rather than returning quietly.
 *
 * <p>The parameter is a {@link SourceAddress}, never a bare string, so no caller can hand an
 * implementation an address that has not already been validated in the domain — the address ends up as an
 * argument to a command run inside the crowdsec container.
 */
public interface ForLiftingBlocks {

    void liftBlock(SourceAddress address);
}
