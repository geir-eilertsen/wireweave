package net.vaier.application;

import net.vaier.domain.MachineId;

/**
 * Abandon a Claude sign-in that was started on a machine and never finished — the operator closed the
 * dialog, or walked away.
 *
 * <p>It exists because the CLI is deliberately left waiting: it runs in a persistent shell so it can
 * survive between the request that started it and the request that hands it a code, which means nothing
 * else would ever stop it. Without this, an abandoned sign-in is a process sitting at a prompt on a
 * machine forever, and an SSH session Vaier holds open for it.
 *
 * <p>Best-effort and idempotent by contract: cancelling a sign-in that is already gone is success.
 */
public interface CancelClaudeSignInUseCase {

    /** End any sign-in waiting on {@code machineId}, and forget everything it held. Never throws. */
    void cancelClaudeSignIn(MachineId machineId);
}
