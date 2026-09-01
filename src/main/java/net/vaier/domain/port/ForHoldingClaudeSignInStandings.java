package net.vaier.domain.port;

import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.MachineId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where the fleet's <b>Claude sign-in standing</b>s live between sweeps — the sibling of
 * {@link ForHoldingMachineDiskStandings}, written on the very same five-minute trip.
 *
 * <p>Ephemeral by nature: a standing is where a machine stood when Vaier last asked it, not a record of
 * anything, so it is held in memory and a restart simply means nothing is known until the next sweep —
 * which is exactly what a card must then draw, nothing.
 *
 * <p>There is deliberately no {@code forget}. Every reading the sweep takes <em>is</em> a standing, even
 * the quiet ones: a machine with no Claude installed, or one that did not answer, is recorded as what it
 * said and drawn as no mark at all. Nothing here is ever removed because it stopped being interesting —
 * only because its machine left the fleet.
 */
public interface ForHoldingClaudeSignInStandings {

    /**
     * Commit what the latest sweep found on one machine, returning the standing it replaces (empty when
     * this machine has never been read). The caller uses that to decide whether anything changed worth
     * waking an open Explorer for.
     */
    Optional<ClaudeSignInStatus> record(ClaudeSignInStatus standing);

    /** Every standing Vaier currently holds, in no particular order. */
    List<ClaudeSignInStatus> getAll();

    /**
     * Drop every standing whose machine is not in {@code machineIds}. Called after a sweep so a machine
     * deleted while Vaier was running does not leave its last sign-in reading behind forever.
     */
    void retainOnly(Set<MachineId> machineIds);
}
