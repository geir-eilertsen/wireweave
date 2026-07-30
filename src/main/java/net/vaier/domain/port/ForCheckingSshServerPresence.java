package net.vaier.domain.port;

import net.vaier.domain.MachineId;
import net.vaier.domain.SshServerPresence;

public interface ForCheckingSshServerPresence {

    /**
     * Vaier's last-known {@link SshServerPresence} for {@code machineId}. {@link SshServerPresence#UNKNOWN}
     * when no observation has landed yet — never observed, or observed ambiguously.
     */
    SshServerPresence getPresence(MachineId machineId);
}
