package net.fjordomatic.application;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.SshServerPresence;

/**
 * Fjord's last-known belief about whether a machine's SSH server is present, so a driving edge — the
 * machine list that feeds the Explorer — can gate SSH-dependent controls without waiting for a click to
 * fail. Never opens a connection itself: it reads what the existing disk-usage sweep already observed.
 */
public interface GetSshServerPresenceUseCase {

    SshServerPresence getSshServerPresence(MachineId machineId);
}
