package net.vaier.adapter.driven;

import net.vaier.domain.DeviceClaim;
import net.vaier.domain.MachineId;
import net.vaier.domain.ReportedPosition;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

class MachinePositionFileAdapterTest {

    private static final Instant NOON = Instant.parse("2026-08-11T12:00:00Z");
    private static final MachineId PHONE = TestMachineIds.of("phone");
    private static final MachineId LAPTOP = TestMachineIds.of("laptop");

    @TempDir
    Path configDir;

    private MachinePositionFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MachinePositionFileAdapter(configDir.toString(), new SecretCipher(configDir.toString()));
    }

    /** A report from the machine's own tunnel — the identity WireGuard authenticated. */
    private void report(MachineId machineId, double latitude, Double accuracy) {
        adapter.recordReportedPosition(machineId, null,
            ReportedPosition.report(latitude, 10.3951, accuracy, NOON));
    }

    /** Nobody has reported a position yet. That is the healthy first boot, not an error. */
    @Test
    void getAll_withNoFileYet_isEmpty() {
        assertThat(adapter.getAll().entries()).isEmpty();
    }

    @Test
    void recordReportedPosition_thenGetAll_roundTripsAReportedPosition() {
        report(PHONE, 63.4305, 12.5);

        assertThat(adapter.getAll().reportedFor(PHONE)).get().satisfies(position -> {
            assertThat(position.latitude()).isEqualTo(63.4305);
            assertThat(position.longitude()).isEqualTo(10.3951);
            assertThat(position.accuracyMetres()).isEqualTo(12.5);
            assertThat(position.reportedAt()).isEqualTo(NOON);
        });
    }

    @Test
    void recordReportedPosition_thenGetAll_roundTripsAPositionWithNoAccuracy() {
        report(PHONE, 63.4305, null);

        assertThat(adapter.getAll().reportedFor(PHONE)).get()
            .satisfies(position -> assertThat(position.accuracyMetres()).isNull());
    }

    @Test
    void saveClaim_thenGetAll_roundTripsADeviceClaim() {
        DeviceClaim claim = DeviceClaim.mint(NOON);

        adapter.saveClaim(PHONE, claim);

        assertThat(adapter.getAll().reportingMachine(null, claim.token())).contains(PHONE);
        assertThat(adapter.getAll().forMachine(PHONE)).get()
            .satisfies(entry -> assertThat(entry.claim().claimedAt()).isEqualTo(NOON));
    }

    /** The claim token is the one secret in this file — it never sits in the clear, like every other. */
    @Test
    void saveClaim_encryptsTheClaimTokenAtRest() throws Exception {
        DeviceClaim claim = DeviceClaim.mint(NOON);

        adapter.saveClaim(PHONE, claim);

        String onDisk = Files.readString(configDir.resolve("machine-positions.yml"));
        assertThat(onDisk).doesNotContain(claim.token());
        assertThat(onDisk).contains(PHONE.value());
    }

    /** One record per machine: reporting again moves the device, it does not add a second dot. */
    @Test
    void recordReportedPosition_replacesTheRecordThatMachineHadBefore() {
        report(PHONE, 59.9139, 40.0);

        report(PHONE, 63.4305, 12.0);

        assertThat(adapter.getAll().entries()).hasSize(1);
        assertThat(adapter.getAll().reportedFor(PHONE)).get()
            .satisfies(position -> assertThat(position.latitude()).isEqualTo(63.4305));
    }

    @Test
    void recordReportedPosition_leavesOtherMachinesAlone() {
        report(PHONE, 63.4305, 12.0);
        report(LAPTOP, 59.9139, 40.0);

        assertThat(adapter.getAll().entries()).hasSize(2);
        assertThat(adapter.getAll().reportedFor(LAPTOP)).isPresent();
    }

    /** The privacy escape hatch: forgetting has to actually leave the file, claim and all. */
    @Test
    void remove_dropsThatMachinesPositionAndClaim() {
        DeviceClaim claim = DeviceClaim.mint(NOON);
        report(PHONE, 63.4305, 12.0);
        adapter.saveClaim(PHONE, claim);
        report(LAPTOP, 59.9139, 40.0);

        adapter.remove(PHONE);

        assertThat(adapter.getAll().reportedFor(PHONE)).isEmpty();
        assertThat(adapter.getAll().reportingMachine(null, claim.token())).isEmpty();
        assertThat(adapter.getAll().reportedFor(LAPTOP)).isPresent();
    }

    @Test
    void remove_aMachineThatNeverReportedIsNotAnError() {
        adapter.remove(PHONE);

        assertThat(adapter.getAll().entries()).isEmpty();
    }

    /** Tolerant on load, like every other file adapter here: one bad entry costs its own dot, not the map. */
    @Test
    void getAll_skipsAnUnreadableEntryAndKeepsTheRest() throws Exception {
        report(PHONE, 63.4305, 12.0);
        String good = Files.readString(configDir.resolve("machine-positions.yml"));
        Files.writeString(configDir.resolve("machine-positions.yml"), good + """
            - machineId: 'not-a-machine-id'
              latitude: 63.4305
              longitude: 10.3951
              reportedAt: '2026-08-11T12:00:00Z'
            """);

        assertThat(adapter.getAll().entries()).singleElement()
            .satisfies(entry -> assertThat(entry.machineId()).isEqualTo(PHONE));
    }

    // --- the position trail ---

    @Test
    void recordReportedPosition_thenGetAll_roundTripsTheTrail() {
        report(PHONE, 63.4305, 12.0);
        adapter.recordReportedPosition(PHONE, null,
            ReportedPosition.report(63.5305, 10.3951, 8.0, NOON.plus(Duration.ofMinutes(20))));

        assertThat(adapter.getAll().trailFor(PHONE, NOON.plus(Duration.ofMinutes(20))).points())
            .hasSize(2)
            .satisfies(points -> {
                assertThat(points.get(0).latitude()).isEqualTo(63.4305);
                assertThat(points.get(1).latitude()).isEqualTo(63.5305);
                assertThat(points.get(1).accuracyMetres()).isEqualTo(8.0);
                assertThat(points.get(1).reportedAt()).isEqualTo(NOON.plus(Duration.ofMinutes(20)));
            });
    }

    /**
     * Whether a report joins the trail is decided once, when it is reported. Re-running the stored current
     * position through that decision on every load would add a point per read — a trail that grows because
     * somebody opened the map.
     */
    @Test
    void getAll_doesNotAddAPointToTheTrailJustForReadingTheFile() {
        report(PHONE, 63.4305, 12.0);

        adapter.getAll();
        adapter.getAll();

        assertThat(adapter.getAll().trailFor(PHONE, NOON).points()).hasSize(1);
    }

    /**
     * The trail is not encrypted, and that is the point: a rotated or lost vault key already costs the
     * claim (which is a bearer token), and it must not also cost the operator a month of their own history.
     */
    @Test
    void getAll_keepsTheTrailWhenTheClaimTokenIsUnreadable() throws Exception {
        report(PHONE, 63.4305, 12.0);
        adapter.saveClaim(PHONE, DeviceClaim.mint(NOON));
        String onDisk = Files.readString(configDir.resolve("machine-positions.yml"));
        Files.writeString(configDir.resolve("machine-positions.yml"),
            onDisk.replaceAll("claimToken: .*", "claimToken: enc:v1:bm90LWEtcmVhbC1lbnZlbG9wZQ=="));

        assertThat(adapter.getAll().trailFor(PHONE, NOON).points()).hasSize(1);
    }

    /**
     * Forgetting must leave no residue on disk — and the trail is the part that would hurt most, being a
     * month of somewhere-you-were rather than one dot. Asserted against the file's own bytes, because
     * {@code getAll} hiding an entry it could no longer read would pass a weaker check.
     */
    @Test
    void remove_leavesNoTrailBehindOnDisk() throws Exception {
        report(PHONE, 63.4305, 12.0);
        adapter.recordReportedPosition(PHONE, null,
            ReportedPosition.report(59.9139, 10.7522, 8.0, NOON.plus(Duration.ofMinutes(20))));
        adapter.saveClaim(PHONE, DeviceClaim.mint(NOON));

        adapter.remove(PHONE);

        String onDisk = Files.readString(configDir.resolve("machine-positions.yml"));
        assertThat(onDisk).doesNotContain("63.4305", "59.9139", "10.7522", PHONE.value());
        assertThat(adapter.getAll().trailFor(PHONE, NOON.plus(Duration.ofMinutes(20))).points()).isEmpty();
    }

    /** One machine's Forget must not take another's trail with it. */
    @Test
    void remove_leavesOtherMachinesTrailsAlone() {
        report(PHONE, 63.4305, 12.0);
        report(LAPTOP, 59.9139, 40.0);

        adapter.remove(PHONE);

        assertThat(adapter.getAll().trailFor(LAPTOP, NOON).points()).hasSize(1);
    }

    /** A trail point that will not read costs itself, not the entry — the same tolerance as everything here. */
    @Test
    void getAll_skipsAnUnreadableTrailPointAndKeepsTheRest() throws Exception {
        report(PHONE, 63.4305, 12.0);
        String onDisk = Files.readString(configDir.resolve("machine-positions.yml"));
        Files.writeString(configDir.resolve("machine-positions.yml"),
            onDisk.replace("  trail:\n", "  trail:\n"
                + "  - latitude: 'not-a-number'\n"
                + "    longitude: 10.3951\n"
                + "    at: '2026-08-11T12:00:00Z'\n"));

        assertThat(adapter.getAll().forMachine(PHONE)).get().satisfies(entry -> {
            assertThat(entry.position()).isNotNull();
            assertThat(entry.trail().points()).hasSize(1);
        });
    }

    // --- concurrent writers: a write must merge into what is on disk, never revert it ---

    /**
     * The race the write-side merge left open: identity used to be decided by the caller, from a read
     * taken before the operator pressed Forget, so an already-revoked claim still named a machine and the
     * late report filed a fresh record — position and trail — for a device Vaier was told to forget.
     * Resolving inside this monitor is what makes that unrepresentable.
     */
    @Test
    void aReportIdentifiedByAClaimForgetRevokedCreatesNothing() {
        DeviceClaim claim = DeviceClaim.mint(NOON);
        adapter.saveClaim(PHONE, claim);
        report(PHONE, 59.9139, 40.0);

        adapter.remove(PHONE);
        // The in-flight report, carrying exactly what the browser holds: its claim token, now revoked.
        Optional<MachineId> attributed = adapter.recordReportedPosition(null, claim.token(),
            ReportedPosition.report(63.4305, 10.3951, 12.0, NOON));

        assertThat(attributed).isEmpty();
        assertThat(adapter.getAll().entries()).isEmpty();
    }

    /**
     * The tunnel is the identity WireGuard authenticated, so a device still on it goes on reporting after
     * a Forget — starting from nothing. What must not come back is the revoked claim.
     */
    @Test
    void aTunnelReportAfterAForgetStartsAgainWithNoClaim() {
        DeviceClaim claim = DeviceClaim.mint(NOON);
        adapter.saveClaim(PHONE, claim);
        report(PHONE, 59.9139, 40.0);

        adapter.remove(PHONE);
        adapter.recordReportedPosition(PHONE, claim.token(),
            ReportedPosition.report(63.4305, 10.3951, 12.0, NOON));

        assertThat(adapter.getAll().reportingMachine(null, claim.token())).isEmpty();
        assertThat(adapter.getAll().forMachine(PHONE)).get().satisfies(entry -> {
            assertThat(entry.claim()).isNull();
            assertThat(entry.trail().points()).hasSize(1);
        });
    }

    /** Both identities present: WireGuard authenticated one of them, and that is the one that counts. */
    @Test
    void recordReportedPosition_prefersTheTunnelOverAClaimNamingAnotherMachine() {
        DeviceClaim claim = DeviceClaim.mint(NOON);
        adapter.saveClaim(LAPTOP, claim);

        Optional<MachineId> attributed = adapter.recordReportedPosition(PHONE, claim.token(),
            ReportedPosition.report(63.4305, 10.3951, 12.0, NOON));

        assertThat(attributed).contains(PHONE);
        assertThat(adapter.getAll().reportedFor(PHONE)).isPresent();
        assertThat(adapter.getAll().reportedFor(LAPTOP)).isEmpty();
    }

    /** Off the tunnel is the ordinary case for a phone, and the live claim is what names it. */
    @Test
    void recordReportedPosition_offTheTunnel_attributesToTheClaimedMachine() {
        DeviceClaim claim = DeviceClaim.mint(NOON);
        adapter.saveClaim(PHONE, claim);

        Optional<MachineId> attributed = adapter.recordReportedPosition(null, claim.token(),
            ReportedPosition.report(63.4305, 10.3951, 12.0, NOON));

        assertThat(attributed).contains(PHONE);
        assertThat(adapter.getAll().reportedFor(PHONE)).isPresent();
    }

    /**
     * The race run for real: the phone's auto-share and the operator's Forget on two threads at once.
     * Whichever order they take, Forget has the last word — the report either lands before it and is
     * erased with everything else, or arrives to find no claim left to name a machine. No interleaving
     * leaves a dot behind, which is only true because identity is decided under the same monitor.
     */
    @Test
    void aReportRacingAForgetLeavesNothingBehind() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            DeviceClaim claim = DeviceClaim.mint(NOON);
            adapter.saveClaim(PHONE, claim);
            CyclicBarrier together = new CyclicBarrier(2);
            Thread reporting = new Thread(() -> {
                arriveAt(together);
                adapter.recordReportedPosition(null, claim.token(),
                    ReportedPosition.report(63.4305, 10.3951, 12.0, NOON));
            });
            Thread forgetting = new Thread(() -> {
                arriveAt(together);
                adapter.remove(PHONE);
            });

            reporting.start();
            forgetting.start();
            reporting.join();
            forgetting.join();

            assertThat(adapter.getAll().entries()).isEmpty();
        }
    }

    private static void arriveAt(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nothing identifies the caller: no machine, and not so much as a file to show for it. */
    @Test
    void recordReportedPosition_withNothingIdentifyingTheCaller_writesNothing() {
        Optional<MachineId> attributed = adapter.recordReportedPosition(null, null,
            ReportedPosition.report(63.4305, 10.3951, 12.0, NOON));

        assertThat(attributed).isEmpty();
        assertThat(configDir.resolve("machine-positions.yml")).doesNotExist();
    }

    /** The mirror case: a forgotten machine's position must not ride back in on a late claim either. */
    @Test
    void aClaimLandingAfterAForgetBringsBackNoPosition() {
        report(PHONE, 63.4305, 12.0);
        adapter.remove(PHONE);

        adapter.saveClaim(PHONE, DeviceClaim.mint(NOON));

        assertThat(adapter.getAll().reportedFor(PHONE)).isEmpty();
    }

    /** Re-claiming from a second browser must not be undone by a report that read the first token. */
    @Test
    void aClaimSupersededMidReportIsNotRevertedByThatReport() {
        DeviceClaim first = DeviceClaim.mint(NOON);
        adapter.saveClaim(PHONE, first);
        DeviceClaim second = DeviceClaim.mint(NOON.plusSeconds(60));

        adapter.saveClaim(PHONE, second);
        report(PHONE, 63.4305, 12.0);

        assertThat(adapter.getAll().reportingMachine(null, first.token())).isEmpty();
        assertThat(adapter.getAll().reportingMachine(null, second.token())).contains(PHONE);
        assertThat(adapter.getAll().reportedFor(PHONE)).isPresent();
    }

    /** And a claim must not wipe a position reported while it was being minted. */
    @Test
    void aPositionReportedMidClaimSurvivesTheClaim() {
        report(PHONE, 63.4305, 12.0);

        adapter.saveClaim(PHONE, DeviceClaim.mint(NOON));

        assertThat(adapter.getAll().reportedFor(PHONE)).get()
            .satisfies(position -> assertThat(position.latitude()).isEqualTo(63.4305));
    }

    // --- at rest ---

    /** The adapter promises vault-grade permissions; nothing asserted it until now. */
    @Test
    void theStoreIsReadableOnlyByVaier() throws Exception {
        adapter.saveClaim(PHONE, DeviceClaim.mint(NOON));

        assertThat(Files.getPosixFilePermissions(configDir.resolve("machine-positions.yml")))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    /**
     * A claim token that will not decrypt — a rotated or lost vault key — costs the claim, not the dot.
     * Taking the position down with it would contradict the tolerance this adapter is built on.
     */
    @Test
    void getAll_keepsThePositionWhenOnlyTheClaimTokenIsUnreadable() throws Exception {
        report(PHONE, 63.4305, 12.0);
        adapter.saveClaim(PHONE, DeviceClaim.mint(NOON));
        String onDisk = Files.readString(configDir.resolve("machine-positions.yml"));
        Files.writeString(configDir.resolve("machine-positions.yml"),
            onDisk.replaceAll("claimToken: .*", "claimToken: enc:v1:bm90LWEtcmVhbC1lbnZlbG9wZQ=="));

        assertThat(adapter.getAll().forMachine(PHONE)).get().satisfies(entry -> {
            assertThat(entry.position()).isNotNull();
            assertThat(entry.claim()).isNull();
        });
    }

    @Test
    void getAll_withAnUnparseableFile_isEmptyRatherThanThrowing() throws Exception {
        Files.writeString(configDir.resolve("machine-positions.yml"), "\t: this is not: yaml: at all\n  - [");

        assertThat(adapter.getAll().entries()).isEmpty();
    }
}
