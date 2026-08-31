package net.vaier.domain;

/**
 * What Vaier last knew about one {@link FleetCredential} on one machine.
 *
 * <p>{@link #SKIPPED} is deliberately not a failure: a phone or a printer has no shell to hold a
 * credential, and a machine Vaier holds no login for is one it may not reach — neither is a problem an
 * operator needs to act on, so neither may ever read as an error.
 */
public enum FleetCredentialState {

    /** The machine does not run a shell Vaier can reach — no SSH access, or no host credential. */
    SKIPPED,

    /** The machine runs a shell, and the credential's file is not on it. */
    MISSING,

    /** The file is there but it is not what it should be — wrong content, wrong mode, or wrong owner. */
    STALE,

    /** The file is there, byte-identical, owned by the login user, at the credential's mode. */
    CURRENT,

    /** The file was removed from the machine, and its absence was confirmed. */
    WITHDRAWN,

    /** Vaier reached the machine and the write, the removal, or the reading of it did not work out. */
    FAILED,

    /** Vaier could not reach the machine at all this time. Not an alert — machines sleep. */
    UNREACHABLE;

    /**
     * Whether the background reconcile may write over this state. Only a hole is filled: a credential
     * that is absent or has drifted. Everything else is left exactly as it is — a
     * {@link #WITHDRAWN} one above all, since a healer that re-pushed a revoked secret would undo the
     * one place revocation happens.
     */
    public boolean needsHealing() {
        return this == MISSING || this == STALE;
    }
}
