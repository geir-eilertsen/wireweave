package net.vaier.application;

import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.MachineId;

/**
 * Where one machine — and specifically the OS user Vaier acts as on it — stands on Claude sign-in.
 *
 * <p><b>One machine, one SSH round trip.</b> This exists rather than a fleet read because the answer is
 * drawn on a machine's own pane, and a fleet read asks <em>every</em> machine over SSH in turn. Reusing
 * one to paint a single pane would put a fleet-wide sweep behind opening any machine, with every sleeping
 * box waited on before anything appeared.
 *
 * <p>The answer is scoped to one user by nature: a Claude sign-in lives in that user's home directory, so
 * it says nothing about any other account on the same machine. The
 * {@link ClaudeSignInStatus#effectiveUser()} it carries is which one it is about.
 *
 * <p>Asked by running the CLI's own {@code claude auth status --json}. Presence of a shell Vaier can
 * reach is checked first, so a phone or a machine with no stored login is answered without being
 * disturbed.
 */
public interface GetClaudeSignInStatusUseCase {

    /**
     * The standing of Vaier's effective user on {@code machineId}. Never throws for a machine that is
     * asleep or cannot answer — that is {@code UNREACHABLE} or {@code UNKNOWN}, which are results.
     *
     * @throws net.vaier.domain.NotFoundException no machine has that id
     */
    ClaudeSignInStatus getClaudeSignInStatus(MachineId machineId);
}
