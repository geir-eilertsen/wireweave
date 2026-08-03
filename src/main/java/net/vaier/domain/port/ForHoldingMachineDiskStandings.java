package net.vaier.domain.port;

import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the fleet's <b>machine disk standing</b>s live between sweeps. Ephemeral by nature, like peer stats:
 * a standing is what Vaier last saw on a machine, not a record of anything, so it is held in memory and a
 * restart simply means nothing is known until the next sweep — which is exactly what a card must then draw,
 * nothing.
 */
public interface ForHoldingMachineDiskStandings {

    /**
     * Commit what the latest sweep found on one machine, returning the standing it replaces (empty when this
     * machine has never been read). The caller uses that to decide whether anything changed worth waking an
     * open Explorer for.
     */
    Optional<MachineDiskStanding> record(MachineDiskStanding standing);

    /**
     * Drop a machine's standing, returning what was dropped (empty when there was nothing). Called when a
     * sweep finds nothing left to judge there — every filesystem muted — so a card stops showing a verdict
     * nobody is making any more.
     */
    Optional<MachineDiskStanding> forget(MachineId machineId);

    /** Every standing Vaier currently holds, in no particular order. */
    List<MachineDiskStanding> getAll();

    /**
     * Drop every standing whose machine is not in {@code machineIds}. Called after a sweep so a machine
     * deleted while Vaier was running does not leave its last disk reading behind forever.
     */
    void retainOnly(Set<MachineId> machineIds);
}
