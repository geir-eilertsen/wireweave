package net.vaier.application;

import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.MachineId;

/**
 * Sign one machine's Claude CLI out of the operator's Anthropic account.
 *
 * <p>Compliant for the same reason the sign-in is: Vaier invokes the unmodified binary — {@code claude
 * auth logout} — and never touches a credential itself. It emphatically does <b>not</b> work by deleting
 * the CLI's credential file. Removing someone's credential from disk would achieve the same end state
 * and is exactly the line Vaier stays behind, because deleting a credential is manipulating one. Vaier
 * asks the binary that owns it to let it go, then asks that same binary whether it did.
 */
public interface SignOutOfClaudeUseCase {

    /**
     * Run the CLI's own sign-out on {@code machineId} and report the machine's refreshed standing, read
     * back from {@code claude auth status --json} rather than assumed from the logout having run.
     *
     * <p>Signing out a machine that was never signed in, or has no Claude installed, is success — there
     * is nothing there to fail at.
     *
     * @throws net.vaier.domain.NotFoundException        the machine id is unknown
     * @throws net.vaier.domain.NoHostCredentialException no credential is stored for the machine
     * @throws net.vaier.domain.SshConnectException      the host could not be reached
     */
    ClaudeSignInStatus signOutOfClaude(MachineId machineId);
}
