package net.vaier.application;

import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;

/**
 * Start the unmodified Claude Code CLI on a machine so its own sign-in flow begins, and hand back the
 * live session so the caller can read Anthropic's authorization URL out of it and later write the
 * operator's code into it.
 *
 * <p>This is the only capability the {@link ClaudeSignIn Claude sign-in} needs from the remote-shell
 * domain, and it is deliberately narrow: it opens a shell running Anthropic's binary and returns it.
 * It does not read the URL, it does not take a code, and it stores nothing — those all belong to the
 * driving adapter that conducts the flow, because none of them is Vaier's to keep.
 */
public interface OpenClaudeSignInShellUseCase {

    /**
     * Open a PTY session on {@code machineId} running {@link ClaudeSignIn#startCommand()}, streaming the
     * CLI's output to {@code onOutput}. Resolves the machine's SSH address, authenticates from the
     * credential vault, and pins the host key on first use — the same path the web terminal takes.
     *
     * <p>The CLI needs a PTY (without one it exits immediately, printing nothing) and has to outlive the
     * request that started it, so it runs inside its own reserved persistent shell.
     *
     * @throws net.vaier.domain.NotFoundException         the machine id is unknown
     * @throws net.vaier.domain.NoHostCredentialException no credential is stored for the machine
     * @throws net.vaier.domain.HostKeyMismatchException  the host key changed from the pinned one
     * @throws net.vaier.domain.SshAuthException          the stored credential was rejected
     * @throws net.vaier.domain.SshConnectException       the host could not be reached
     */
    SshSession openClaudeSignInShell(MachineId machineId, SshOutputListener onOutput);
}
