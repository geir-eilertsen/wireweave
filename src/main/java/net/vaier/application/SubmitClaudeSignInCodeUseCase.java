package net.vaier.application;

import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.MachineId;

/**
 * Hand the code Anthropic showed the operator to the Claude CLI waiting on a machine, and report where
 * that machine ended up.
 *
 * <p>The code is written into the waiting process as keystrokes and is not kept afterwards — not in
 * memory beyond the write, not on disk, not in a log. Vaier is a relay for it, never a holder of it.
 *
 * <p>The reported status is read back by asking the CLI — {@code claude auth status --json} — rather
 * than taken from what it printed. A screen-scrape saying "Login successful" over a sign-in that did not
 * land would be Vaier lying about a machine's state; only the CLI's own answer settles it.
 */
public interface SubmitClaudeSignInCodeUseCase {

    /**
     * Write {@code code} into the sign-in waiting on {@code machineId}, end that sign-in, and return the
     * machine's resulting standing.
     *
     * @throws net.vaier.domain.NotFoundException no sign-in is waiting on that machine
     * @throws IllegalArgumentException           the code is blank or is not a plain authorization code
     */
    ClaudeSignInStatus submitClaudeSignInCode(MachineId machineId, String code);
}
