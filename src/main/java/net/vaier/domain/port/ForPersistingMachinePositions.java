package net.vaier.domain.port;

import net.vaier.domain.DeviceClaim;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachinePositions;
import net.vaier.domain.ReportedPosition;

import java.util.Optional;

/**
 * The store of where machines say they are, and which browser is allowed to say it.
 *
 * <p>The writes are deliberately <b>intent-shaped</b> — one field each, never a whole record. A caller
 * that read a record and handed it back would revert whatever changed in between, and the change most
 * likely to be in flight is a {@code Forget}: the phone auto-shares on every page load, so a concurrent
 * writer is the normal case. Merging one field into what is currently stored, inside the store's own
 * lock, is what makes a revoked claim stay revoked.
 *
 * <p>For the same reason a report says <em>who is talking</em> rather than which machine to file it
 * under: deciding that outside the store leaves a gap in which the claim can be revoked, and the report
 * then lands under an identity that no longer exists.
 */
public interface ForPersistingMachinePositions {

    MachinePositions getAll();

    /**
     * Records where the calling device says it is, against whichever machine identifies it — resolved by
     * {@link MachinePositions#reportingMachine} against what is stored, inside the store's own lock.
     *
     * <p>Returns the machine it was attributed to, or empty when nothing identifies the caller — in which
     * case nothing at all is written, not a record, not a position and not a trail point. That is what
     * makes {@code Forget} absolute: a claim revoked a moment ago cannot still name a machine here.
     *
     * @param fromTunnel the machine whose tunnel the request came from, or null when it came from
     *                   elsewhere. Never a machine id the caller asked for.
     * @param claimToken the device claim the browser carries, or null.
     */
    Optional<MachineId> recordReportedPosition(MachineId fromTunnel, String claimToken,
                                               ReportedPosition position);

    /** Binds that machine to a browser, leaving any position it has reported exactly as it stands. */
    void saveClaim(MachineId machineId, DeviceClaim claim);

    /** Forgets that machine entirely: position and claim, which go together. */
    void remove(MachineId machineId);
}
