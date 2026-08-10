package net.fjordomatic.application;

/**
 * Lets one blocked address back in now (#329 Slice 3c) — the operator's answer to a false positive, most
 * often their own address blocked from a network Fjord does not know is theirs.
 *
 * <p>One-off, not permanent: the address can be blocked again the next moment a CrowdSec scenario matches
 * it. {@link TrustAddressUseCase} is the permanent form.
 *
 * @throws IllegalArgumentException              if {@code sourceIp} is not a plain IPv4 address
 * @throws net.fjordomatic.domain.BlockNotLiftedException if the block could not be lifted — never silence
 */
public interface LiftBlockUseCase {

    void liftBlock(String sourceIp);
}
