package net.vaier.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Every machine's {@link MachinePosition}, and the decisions that read across them. */
public record MachinePositions(List<MachinePosition> entries) {

    public MachinePositions {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static MachinePositions of(List<MachinePosition> entries) {
        return new MachinePositions(entries);
    }

    public static MachinePositions empty() {
        return new MachinePositions(List.of());
    }

    public Optional<MachinePosition> forMachine(MachineId machineId) {
        if (machineId == null) return Optional.empty();
        return entries.stream().filter(entry -> entry.machineId().isSameAs(machineId)).findFirst();
    }

    /** What that machine last said about where it is, if anything. */
    public Optional<ReportedPosition> reportedFor(MachineId machineId) {
        return forMachine(machineId).map(MachinePosition::position).filter(position -> position != null);
    }

    /**
     * Where that machine has been, as it should be shown at {@code now} — empty for a machine that never
     * reported. Retention is applied here rather than only on write, so a device that stopped reporting
     * still stops being drawn once its trail ages out.
     */
    public PositionTrail trailFor(MachineId machineId, Instant now) {
        return forMachine(machineId).map(MachinePosition::trail).orElseGet(PositionTrail::empty)
            .retained(now);
    }

    /**
     * Which machine a position report is about.
     *
     * <p><b>The tunnel wins whenever it names one.</b> WireGuard authenticated that packet, so its source
     * address cannot be forged; a {@link DeviceClaim} is a cookie a browser carries, which could be copied.
     * Neither identifies a machine → empty, and the caller refuses the report rather than guessing.
     *
     * <p>A machine id is never taken from the caller's own request: a device may report only its own
     * position, and this is where that is enforced.
     */
    public Optional<MachineId> reportingMachine(MachineId fromTunnel, String claimToken) {
        if (fromTunnel != null) return Optional.of(fromTunnel);
        return claimedBy(claimToken);
    }

    /** The machine this token claims, if any token still does. */
    public Optional<MachineId> claimedBy(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return entries.stream()
            .filter(entry -> entry.isClaimedBy(token))
            .map(MachinePosition::machineId)
            .findFirst();
    }

    /**
     * That machine's record with only its position replaced, started from scratch when it has none.
     *
     * <p>Combining one field into whatever is currently held is what makes revocation stick: a writer
     * that carried a whole record read moments earlier would restore the claim it happened to be
     * holding, undoing a {@code Forget} the operator has already been told succeeded.
     */
    public MachinePositions withPositionFor(MachineId machineId, ReportedPosition reported) {
        return with(recordFor(machineId).withPosition(reported));
    }

    /** That machine's record with only its claim replaced — superseding whatever claimed it before. */
    public MachinePositions withClaimFor(MachineId machineId, DeviceClaim claim) {
        return with(recordFor(machineId).withClaim(claim));
    }

    private MachinePosition recordFor(MachineId machineId) {
        return forMachine(machineId).orElseGet(() -> MachinePosition.forMachine(machineId));
    }

    /** This record in place of that machine's previous one, leaving every other machine alone. */
    public MachinePositions with(MachinePosition entry) {
        List<MachinePosition> updated = new ArrayList<>(without(entry.machineId()).entries());
        updated.add(entry);
        return new MachinePositions(updated);
    }

    /**
     * Forgetting takes the position, the trail and the claim together — a claim outliving its position
     * would track, and a trail outliving either is a month of somebody's whereabouts they were told was
     * gone. One record, one act.
     */
    public MachinePositions without(MachineId machineId) {
        return new MachinePositions(entries.stream()
            .filter(entry -> !entry.machineId().isSameAs(machineId))
            .toList());
    }
}
