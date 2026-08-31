package net.vaier.application;

public interface DeleteFleetCredentialUseCase {

    /**
     * Forget the fleet credential stored under {@code name}. This removes Vaier's copy; it does not reach
     * any machine — withdrawing a distributed credential from the fleet is
     * {@link WithdrawFleetCredentialUseCase}, and it is the one that revokes.
     */
    void deleteFleetCredential(String name);
}
