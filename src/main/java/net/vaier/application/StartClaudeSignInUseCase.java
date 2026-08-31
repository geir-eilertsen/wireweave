package net.vaier.application;

import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.ClaudeSignInFailedException;
import net.vaier.domain.MachineId;

/**
 * Begin a {@link ClaudeSignIn} on one machine and hand the operator the authorization URL Anthropic's
 * own CLI printed there.
 *
 * <p>The returned URL is Anthropic's, produced on the machine, passed straight through to the
 * operator's browser. Vaier does not store it, log it, or shorten it — it relays it and forgets it.
 */
public interface StartClaudeSignInUseCase {

    /**
     * Start the unmodified Claude CLI on {@code machineId} and return the authorization URL it printed.
     * The CLI is left waiting at its "paste code here" prompt for
     * {@link SubmitClaudeSignInCodeUseCase}.
     *
     * <p>A machine that is already signed in may be signed in again — {@code claude auth login} prints a
     * fresh authorization URL either way — so moving one onto a different account, or replacing a
     * credential that has gone bad, is just another sign-in.
     *
     * @throws ClaudeSignInFailedException        Claude Code is not installed there, or no URL could be
     *                                            read from the CLI's output within the bounded wait
     * @throws net.vaier.domain.NotFoundException the machine id is unknown
     */
    String startClaudeSignIn(MachineId machineId);
}
