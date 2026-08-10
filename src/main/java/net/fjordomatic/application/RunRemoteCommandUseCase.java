package net.fjordomatic.application;

import net.fjordomatic.domain.CommandResult;
import net.fjordomatic.domain.HostKeyMismatchException;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.NoHostCredentialException;
import net.fjordomatic.domain.NotFoundException;
import net.fjordomatic.domain.SshAuthException;
import net.fjordomatic.domain.SshConnectException;

public interface RunRemoteCommandUseCase {

    /**
     * Run {@code command} over SSH on the machine identified by {@code machineId} and return its captured
     * output and exit status. Resolves the machine's SSH address, authenticates from the credential
     * vault, and pins the host key on first use (TOFU) — the same resolution + credential + pin logic
     * the web terminal uses. A non-zero exit code is a normal result, never an exception.
     *
     * <p>Keyed by identity, not by name: a command is a side effect on a specific machine, and a name is
     * a label an operator may edit between the moment a caller looked a machine up and the moment the
     * command runs. The one thing that must not happen is a command intended for one machine landing on
     * another that has since taken its name.
     *
     * @throws NotFoundException         no machine has that id
     * @throws NoHostCredentialException no credential is stored for the machine
     * @throws HostKeyMismatchException  the host key changed from the pinned one
     * @throws SshAuthException          the stored credential was rejected
     * @throws SshConnectException       the host could not be reached
     */
    CommandResult run(MachineId machineId, String command);
}
