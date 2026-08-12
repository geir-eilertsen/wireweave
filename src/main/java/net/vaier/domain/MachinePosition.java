package net.vaier.domain;

/**
 * What Vaier stores about one machine's whereabouts: the {@link ReportedPosition} the device last sent, the
 * {@link PositionTrail} of where it has been, and the {@link DeviceClaim} naming the browser allowed to
 * send any of it. All three are optional — a machine can be claimed before it ever reports, and can report
 * from the tunnel without ever being claimed.
 *
 * <p>Keyed by {@link MachineId}, never by name or VPN IP: both are mutable labels, and a position filed
 * under one would silently move to another machine the day it changed.
 */
public record MachinePosition(MachineId machineId, ReportedPosition position, DeviceClaim claim,
                              PositionTrail trail) {

    public MachinePosition {
        if (machineId == null) {
            throw new IllegalArgumentException("A machine position must belong to a machine");
        }
        trail = trail == null ? PositionTrail.empty() : trail;
    }

    /** An empty record for a machine that has neither reported nor been claimed yet. */
    public static MachinePosition forMachine(MachineId machineId) {
        return new MachinePosition(machineId, null, null, PositionTrail.empty());
    }

    /**
     * This record after a fresh report: the machine's current position, and the trail offered the same
     * point. Whether the trail actually takes it is the {@link PositionTrail}'s own call — where a machine
     * is and where it has been are two answers to two questions, kept together so no caller can update one
     * and forget the other.
     */
    public MachinePosition withPosition(ReportedPosition reported) {
        return new MachinePosition(machineId, reported, claim, trail.extendedWith(reported));
    }

    /** A fresh claim supersedes the last: one record, one token, so the old one stops working. */
    public MachinePosition withClaim(DeviceClaim newClaim) {
        return new MachinePosition(machineId, position, newClaim, trail);
    }

    public MachinePosition withoutClaim() {
        return new MachinePosition(machineId, position, null, trail);
    }

    /** Whether {@code token} is the claim on this record. False when nothing has claimed it. */
    public boolean isClaimedBy(String token) {
        return claim != null && claim.matches(token);
    }
}
