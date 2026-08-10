package net.fjordomatic.domain.port;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.SshServerPresence;

public interface ForCheckingSshServerPresence {

    /**
     * Fjord's last-known {@link SshServerPresence} for {@code machineId}. {@link SshServerPresence#UNKNOWN}
     * when no observation has landed yet — never observed, or observed ambiguously.
     */
    SshServerPresence getPresence(MachineId machineId);
}
