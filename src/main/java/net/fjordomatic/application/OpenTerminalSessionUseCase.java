package net.fjordomatic.application;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.PersistentShell;
import net.fjordomatic.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.fjordomatic.domain.port.ForOpeningSshSessions.SshSession;

public interface OpenTerminalSessionUseCase {

    /**
     * Open a live SSH shell to the machine named {@code machineId} for the browser pane {@code paneId},
     * streaming remote output to {@code onOutput}. Resolves the machine's SSH address (peer tunnel IP /
     * LAN address / Fjord host), authenticates from the credential vault, and pins the host key on first
     * use.
     *
     * <p>The shell is opened as a <b>persistent shell</b>: a tmux session named for the pane (stable
     * across reconnects, distinct between panes), so it outlives a Fjord redeploy and a reconnect
     * <b>reattaches</b> to it rather than starting fresh. When tmux is not installed on the target a plain
     * login shell is opened instead. The returned {@link OpenedTerminal} carries the live session together
     * with the {@link PersistentShell.Continuity continuity} — whether this open reattached, started a new
     * session, or fell back to a plain shell — so the caller can report it truthfully.
     *
     * @throws net.fjordomatic.domain.NotFoundException        the machine name is unknown
     * @throws net.fjordomatic.domain.NoHostCredentialException no credential is stored for the machine
     * @throws net.fjordomatic.domain.HostKeyMismatchException the host key changed from the pinned one
     * @throws net.fjordomatic.domain.SshAuthException         the stored credential was rejected
     * @throws net.fjordomatic.domain.SshConnectException      the host could not be reached
     */
    OpenedTerminal openTerminal(MachineId machineId, String paneId, SshOutputListener onOutput);

    /**
     * The live {@link SshSession} and how the open resolved (reattached / new / plain).
     */
    record OpenedTerminal(SshSession session, PersistentShell.Continuity continuity) {
    }
}
