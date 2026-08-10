package net.fjordomatic.domain.port;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.SshServerPresence;

import java.util.Set;

public interface ForRecordingSshServerPresence {

    /**
     * Commit an observed {@link SshServerPresence} for {@code machineId}, returning the previously recorded
     * value ({@link SshServerPresence#UNKNOWN} if this machine was never observed before). The caller uses
     * the returned value to decide whether this observation crossed a boundary worth telling the Explorer
     * about.
     */
    SshServerPresence record(MachineId machineId, SshServerPresence presence);

    /**
     * Drop every cached entry whose machine is not in {@code machineIds}. Called after a sweep so a deleted
     * machine's stale presence never lingers.
     */
    void retainOnly(Set<MachineId> machineIds);
}
