package net.vaier.application;

/**
 * A phone asks whether it is still a member of the fleet (#359 slice 3). Nothing pushes a removal to a
 * phone: the first it notices is its handshakes stopping, which looks exactly like a bad network. So it
 * asks, with the same proof {@link LeaveFleetUseCase} takes, and either reconnects or forgets itself.
 */
public interface CheckStandingUseCase {
    /**
     * @return true when the fleet holds a peer these two keys prove; false for an unknown key and a
     *         wrong preshared key alike, so nothing is learned by trying
     * @throws IllegalArgumentException if either key is missing
     */
    boolean isMember(String publicKey, String presharedKey);
}
