package net.vaier.domain;

/**
 * The redacted, safe-to-leave-the-process shape of a {@link HostCredential}: it reports which machine
 * the credential is for (by {@link MachineId}, not by a label that can change under it), its username and auth method, and merely <em>whether</em> a secret is held —
 * never the secret or passphrase bytes. This is the only shape a GET may return to the browser.
 *
 * <p>{@code managed} says whether Vaier generated this credential's keypair itself. It is safe to expose
 * and the browser needs it: a managed keypair's private half is not something an operator can usefully
 * edit — offering them the field would imply otherwise — so the dialog shows the public key instead.
 */
public record HostCredentialView(MachineId machineId, String username, AuthMethod authMethod,
                                 boolean hasSecret, boolean managed) {
}
