package net.vaier.application;

/**
 * A phone removes itself from the fleet (#359 slice 1b). Forgetting the tunnel locally leaves the
 * peer standing on Vaier, which is how a fleet fills up with devices nobody has any more.
 *
 * <p>The phone has no session, so what it presents instead is the preshared key it was handed at
 * approval — a secret only it and Vaier hold. See {@code PeerProof} for the judgement itself.
 */
public interface LeaveFleetUseCase {

    /**
     * Removes the peer these two keys prove, with the same cascade an operator's delete runs.
     *
     * @return false when nothing in the fleet is proved — an unknown key and a wrong preshared key
     *         are deliberately the same answer
     * @throws IllegalArgumentException if either key is missing
     */
    boolean leave(String publicKey, String presharedKey);
}
