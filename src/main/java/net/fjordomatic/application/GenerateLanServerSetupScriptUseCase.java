package net.fjordomatic.application;

import net.fjordomatic.domain.ConflictException;
import net.fjordomatic.domain.MachineId;

import java.util.Optional;

public interface GenerateLanServerSetupScriptUseCase {

    /**
     * Renders the single per-host setup script for a registered LAN server: opens the Docker engine
     * API when the host runs Docker, and installs routes via its relay peer when it is relay-anchored.
     * Empty when the server is unknown or has nothing to set up (no Docker and not relay-anchored).
     * Throws {@link ConflictException} when the relay peer has no LAN address to route via.
     *
     * <p>Addressed by identity: the script is minted against a machine and then run on it, and a rename
     * between those two moments must not be able to point the command at a different host.
     */
    Optional<String> generateSetupScript(MachineId machineId);
}
