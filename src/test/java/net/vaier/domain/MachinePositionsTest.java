package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MachinePositionsTest {

    private static final Instant NOW = Instant.parse("2026-08-11T18:00:00Z");
    private static final MachineId PHONE = TestMachineIds.of("phone");
    private static final MachineId LAPTOP = TestMachineIds.of("laptop");

    private static MachinePosition reporting(MachineId machineId, double latitude) {
        return MachinePosition.forMachine(machineId)
            .withPosition(ReportedPosition.report(latitude, 10.39, 12.0, NOW));
    }

    // --- reading a machine's own record ---

    @Test
    void reportedFor_findsThePositionThatMachineReported() {
        MachinePositions positions = MachinePositions.of(List.of(
            reporting(PHONE, 63.43), reporting(LAPTOP, 59.91)));

        assertThat(positions.reportedFor(PHONE)).get()
            .satisfies(p -> assertThat(p.latitude()).isEqualTo(63.43));
    }

    @Test
    void reportedFor_isEmptyForAMachineThatNeverReported() {
        assertThat(MachinePositions.empty().reportedFor(PHONE)).isEmpty();
    }

    /** A peer with no stored machine id cannot have reported anything — and must not match by accident. */
    @Test
    void reportedFor_isEmptyForNoMachineAtAll() {
        assertThat(MachinePositions.of(List.of(reporting(PHONE, 63.43))).reportedFor(null)).isEmpty();
    }

    /** A record can hold a claim and no position yet — a browser claimed the device before it reported. */
    @Test
    void reportedFor_isEmptyForAClaimedMachineThatHasNotReportedYet() {
        MachinePositions positions = MachinePositions.of(List.of(
            MachinePosition.forMachine(PHONE).withClaim(DeviceClaim.mint(NOW))));

        assertThat(positions.reportedFor(PHONE)).isEmpty();
    }

    // --- which machine is reporting ---

    /**
     * The tunnel wins. WireGuard authenticated the packet, so the source address cannot be forged;
     * a claim is a cookie a browser carries and could be copied.
     */
    @Test
    void reportingMachine_prefersTheTunnelWhenBothIdentitiesArePresent() {
        DeviceClaim claim = DeviceClaim.mint(NOW);
        MachinePositions positions = MachinePositions.of(List.of(
            MachinePosition.forMachine(LAPTOP).withClaim(claim)));

        assertThat(positions.reportingMachine(PHONE, claim.token())).contains(PHONE);
    }

    @Test
    void reportingMachine_fallsBackToAValidClaimOffTheTunnel() {
        DeviceClaim claim = DeviceClaim.mint(NOW);
        MachinePositions positions = MachinePositions.of(List.of(
            MachinePosition.forMachine(PHONE).withClaim(claim)));

        assertThat(positions.reportingMachine(null, claim.token())).contains(PHONE);
    }

    @Test
    void reportingMachine_isEmptyForAnUnclaimedCallerOffTheTunnel() {
        assertThat(MachinePositions.empty().reportingMachine(null, "not-a-token")).isEmpty();
        assertThat(MachinePositions.empty().reportingMachine(null, null)).isEmpty();
        assertThat(MachinePositions.empty().reportingMachine(null, "")).isEmpty();
    }

    /** Revoking is clearing the record; the token the browser still holds must stop naming anything. */
    @Test
    void reportingMachine_isEmptyOnceTheClaimIsGone() {
        DeviceClaim claim = DeviceClaim.mint(NOW);
        MachinePositions revoked = MachinePositions.of(List.of(
            MachinePosition.forMachine(PHONE).withClaim(claim).withoutClaim()));

        assertThat(revoked.reportingMachine(null, claim.token())).isEmpty();
    }

    /** Last claim wins: the record holds one token, so the superseded one names nothing. */
    @Test
    void reportingMachine_isEmptyForATokenASecondClaimSuperseded() {
        DeviceClaim first = DeviceClaim.mint(NOW);
        DeviceClaim second = DeviceClaim.mint(NOW.plusSeconds(60));
        MachinePositions positions = MachinePositions.of(List.of(
            MachinePosition.forMachine(PHONE).withClaim(first).withClaim(second)));

        assertThat(positions.reportingMachine(null, first.token())).isEmpty();
        assertThat(positions.reportingMachine(null, second.token())).contains(PHONE);
    }

    // --- merging one field at a time (what the store's writes are built from) ---

    /**
     * The merge that makes revocation stick. A writer carrying a whole stale record would restore the
     * claim it happened to be holding; combining a single field into whatever is on disk cannot.
     */
    @Test
    void withPositionFor_leavesTheClaimOnDiskExactlyAsItFindsIt() {
        DeviceClaim live = DeviceClaim.mint(NOW);
        MachinePositions positions = MachinePositions.of(List.of(
            MachinePosition.forMachine(PHONE).withClaim(live)));

        MachinePositions updated = positions.withPositionFor(
            PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW));

        assertThat(updated.reportingMachine(null, live.token())).contains(PHONE);
        assertThat(updated.reportedFor(PHONE)).isPresent();
    }

    @Test
    void withPositionFor_startsARecordForAMachineWithNoneYet() {
        MachinePositions updated = MachinePositions.empty()
            .withPositionFor(PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW));

        assertThat(updated.reportedFor(PHONE)).isPresent();
        assertThat(updated.forMachine(PHONE)).get()
            .satisfies(entry -> assertThat(entry.claim()).isNull());
    }

    @Test
    void withClaimFor_leavesThePositionOnDiskExactlyAsItFindsIt() {
        MachinePositions positions = MachinePositions.of(List.of(reporting(PHONE, 63.43)));

        MachinePositions updated = positions.withClaimFor(PHONE, DeviceClaim.mint(NOW));

        assertThat(updated.reportedFor(PHONE)).get()
            .satisfies(p -> assertThat(p.latitude()).isEqualTo(63.43));
    }

    @Test
    void withClaimFor_supersedesWhateverClaimedThatMachineBefore() {
        DeviceClaim first = DeviceClaim.mint(NOW);
        DeviceClaim second = DeviceClaim.mint(NOW.plusSeconds(60));
        MachinePositions positions = MachinePositions.of(List.of(
            MachinePosition.forMachine(PHONE).withClaim(first)));

        MachinePositions updated = positions.withClaimFor(PHONE, second);

        assertThat(updated.reportingMachine(null, first.token())).isEmpty();
        assertThat(updated.reportingMachine(null, second.token())).contains(PHONE);
    }

    @Test
    void withPositionFor_andWithClaimFor_leaveOtherMachinesAlone() {
        MachinePositions positions = MachinePositions.of(List.of(reporting(LAPTOP, 59.91)));

        MachinePositions updated = positions
            .withPositionFor(PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW))
            .withClaimFor(PHONE, DeviceClaim.mint(NOW));

        assertThat(updated.entries()).hasSize(2);
        assertThat(updated.reportedFor(LAPTOP)).get()
            .satisfies(p -> assertThat(p.latitude()).isEqualTo(59.91));
    }

    // --- the position trail: where the machine has been, not only where it is ---

    /** The first report is both the current position and the first point of a trail. */
    @Test
    void withPositionFor_startsTheTrailWithTheMachinesFirstReport() {
        MachinePositions updated = MachinePositions.empty()
            .withPositionFor(PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW));

        assertThat(updated.trailFor(PHONE, NOW).points()).singleElement()
            .satisfies(point -> assertThat(point.latitude()).isEqualTo(63.43));
    }

    /** Travelling is what a trail is for: each meaningful move joins it, the current position moves on. */
    @Test
    void withPositionFor_growsTheTrailAsTheDeviceTravels() {
        MachinePositions updated = MachinePositions.empty()
            .withPositionFor(PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW))
            .withPositionFor(PHONE, ReportedPosition.report(63.50, 10.39, 12.0, NOW.plusSeconds(600)))
            .withPositionFor(PHONE, ReportedPosition.report(63.60, 10.39, 12.0, NOW.plusSeconds(1200)));

        assertThat(updated.trailFor(PHONE, NOW.plusSeconds(1200)).points()).hasSize(3);
        assertThat(updated.reportedFor(PHONE)).get()
            .satisfies(position -> assertThat(position.latitude()).isEqualTo(63.60));
    }

    /** A trail is only ever built from reported positions, so a machine that never reported has none. */
    @Test
    void trailFor_isEmptyForAMachineThatNeverReported() {
        assertThat(MachinePositions.empty().trailFor(PHONE, NOW).points()).isEmpty();
        assertThat(MachinePositions.of(List.of(reporting(PHONE, 63.43))).trailFor(null, NOW).points())
            .isEmpty();
    }

    @Test
    void trailFor_dropsWhatHasAgedOutOfRetention() {
        MachinePositions positions = MachinePositions.empty()
            .withPositionFor(PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW));

        assertThat(positions.trailFor(PHONE, NOW.plus(PositionTrail.RETENTION).plusSeconds(3600)).points())
            .isEmpty();
    }

    @Test
    void withClaimFor_leavesTheTrailOnDiskExactlyAsItFindsIt() {
        MachinePositions positions = MachinePositions.of(List.of(reporting(PHONE, 63.43)));

        MachinePositions updated = positions.withClaimFor(PHONE, DeviceClaim.mint(NOW));

        assertThat(updated.trailFor(PHONE, NOW).points()).hasSize(1);
    }

    /**
     * Forgetting takes the whole trail. A trail surviving a Forget is the worst version of this bug:
     * the operator is told their whereabouts are gone while a month of them stays on disk.
     */
    @Test
    void without_takesTheWholeTrailWithIt() {
        MachinePositions positions = MachinePositions.empty()
            .withPositionFor(PHONE, ReportedPosition.report(63.43, 10.39, 12.0, NOW))
            .withPositionFor(PHONE, ReportedPosition.report(63.50, 10.39, 12.0, NOW.plusSeconds(600)));

        MachinePositions updated = positions.without(PHONE);

        assertThat(updated.trailFor(PHONE, NOW.plusSeconds(600)).points()).isEmpty();
    }

    // --- upserting ---

    @Test
    void with_replacesThatMachinesRecordAndLeavesOthersAlone() {
        MachinePositions positions = MachinePositions.of(List.of(
            reporting(PHONE, 63.43), reporting(LAPTOP, 59.91)));

        MachinePositions updated = positions.with(reporting(PHONE, 10.0));

        assertThat(updated.entries()).hasSize(2);
        assertThat(updated.reportedFor(PHONE)).get()
            .satisfies(p -> assertThat(p.latitude()).isEqualTo(10.0));
        assertThat(updated.reportedFor(LAPTOP)).get()
            .satisfies(p -> assertThat(p.latitude()).isEqualTo(59.91));
    }

    @Test
    void without_dropsThatMachinesRecordEntirely() {
        MachinePositions positions = MachinePositions.of(List.of(
            reporting(PHONE, 63.43), reporting(LAPTOP, 59.91)));

        MachinePositions updated = positions.without(PHONE);

        assertThat(updated.entries()).hasSize(1);
        assertThat(updated.reportedFor(PHONE)).isEmpty();
    }

    /** Forgetting is one action: the position and the claim go together, never one without the other. */
    @Test
    void without_alsoTakesTheClaimWithIt() {
        DeviceClaim claim = DeviceClaim.mint(NOW);
        MachinePositions positions = MachinePositions.of(List.of(
            reporting(PHONE, 63.43).withClaim(claim)));

        MachinePositions updated = positions.without(PHONE);

        assertThat(updated.reportingMachine(null, claim.token())).isEmpty();
        assertThat(updated.reportedFor(PHONE)).isEmpty();
    }
}
