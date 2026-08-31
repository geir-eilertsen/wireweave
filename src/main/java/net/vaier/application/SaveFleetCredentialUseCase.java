package net.vaier.application;

import net.vaier.domain.FleetCredential;

public interface SaveFleetCredentialUseCase {

    /**
     * Store {@code credential} in the vault, replacing any credential of the same name. Storing does not
     * distribute: nothing reaches a machine until the operator says so.
     *
     * <p>A save over a live credential keeps that credential's distribution standing, so editing a path
     * or a mode cannot silently take it off the background reconcile.
     */
    void saveFleetCredential(FleetCredential credential);
}
