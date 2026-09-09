package net.vaier.application;

import net.vaier.domain.CommandOutcome;
import net.vaier.domain.MachineId;

/**
 * Run one <b>Read-only command</b> on a machine over SSH, as Vaier's login user there, and read what it
 * printed (#360). The command is judged by the domain before anything is resolved or connected.
 *
 * <p>Throws {@code IllegalArgumentException} when the command is refused, worded for the one who asked;
 * {@code NotFoundException} when no machine has that id; {@code NoHostCredentialException} when Vaier holds
 * no login for it; and the domain SSH exceptions when the machine could not be reached.
 */
public interface RunReadOnlyCommandUseCase {

    CommandOutcome runReadOnly(MachineId machineId, String command);
}
