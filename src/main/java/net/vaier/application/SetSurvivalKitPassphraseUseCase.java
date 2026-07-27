package net.vaier.application;

/**
 * Choose the passphrase that opens this fleet's survival kit.
 *
 * <p>The operator picks it, and keeps it in their head — it is the one thing they carry for the day there is
 * no Vaier left to ask. Vaier stores it too (in the credential vault), because a passphrase Vaier does not
 * hold is a kit Vaier cannot rewrite, and a kit that stops being rewritten is exactly the stale sheet this
 * whole feature replaced.
 */
public interface SetSurvivalKitPassphraseUseCase {

    /**
     * Store {@code passphrase} as the fleet's kit passphrase.
     *
     * <p>Kits already distributed were encrypted with the <em>old</em> one and do not change by being
     * re-keyed here; they stay readable with the passphrase they were written with until the fleet is
     * written again.
     *
     * @throws IllegalArgumentException when blank — an unprotected kit is indistinguishable from a protected
     *                                  one and gives away every backup in the fleet
     */
    void setSurvivalKitPassphrase(String passphrase);
}
