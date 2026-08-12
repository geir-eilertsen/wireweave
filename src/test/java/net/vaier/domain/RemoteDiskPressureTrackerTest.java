package net.vaier.domain;

import net.vaier.adapter.driven.InMemoryDiskPressureStateAdapter;
import net.vaier.domain.port.ForPersistingDiskPressureState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteDiskPressureTrackerTest {

    private static final MachineId NAS = TestMachineIds.of("nas");
    private static final MachineId NUC = TestMachineIds.of("nuc");

    private ForPersistingDiskPressureState store;
    private RemoteDiskPressureTracker tracker;

    @BeforeEach
    void setUp() {
        store = new InMemoryDiskPressureStateAdapter();
        tracker = new RemoteDiskPressureTracker(store);
    }

    /** A reading of {@code mountPoint} at {@code usedPercent}. Only the percent and the mount matter here. */
    private static RemoteDiskUsage reading(String machineName, String mountPoint, int usedPercent) {
        return new RemoteDiskUsage(machineName, "/dev/sda1", mountPoint, 100, usedPercent,
            100 - usedPercent, usedPercent);
    }

    /** The threshold these readings are judged against, unless a test names another. */
    private static final int THRESHOLD = 80;

    private RemoteDiskPressureTracker.Verdict observe(MachineId machineId, String mountPoint, int usedPercent) {
        return observe(machineId, mountPoint, usedPercent, THRESHOLD);
    }

    private RemoteDiskPressureTracker.Verdict observe(MachineId machineId, String mountPoint, int usedPercent,
                                                      int thresholdPercent) {
        return observe(machineId, reading("nas", mountPoint, usedPercent), thresholdPercent);
    }

    /** Judged exactly as the watcher judges it — the reading's own verdict, never a hand-asserted flag. */
    private RemoteDiskPressureTracker.Verdict observe(MachineId machineId, RemoteDiskUsage filesystem,
                                                      int thresholdPercent) {
        DiskWatch watch = DiskWatch.watchedByDefault(machineId, filesystem.mountPoint());
        return tracker.observe(machineId, filesystem, filesystem.judge(watch, thresholdPercent));
    }

    @Test
    void aFilesystemAlreadyAboveThresholdOnItsVeryFirstObservation_alertsImmediately() {
        // THE BUG. The Vaier host's root filesystem was at 89% — above the threshold — before Vaier had ever
        // observed it. The old tracker treated a first observation as a silent baseline, so an
        // already-breaching filesystem could never produce a crossing and never alerted. With the state
        // persisted, "first ever" now genuinely means first ever, so the first observation must speak.
        assertThat(observe(NAS, "/", 89).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void aFilesystemBelowThresholdOnItsFirstObservation_saysNothing() {
        assertThat(observe(NAS, "/", 40).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.QUIET);
    }

    @Test
    void crossingIntoPressure_alerts() {
        observe(NAS, "/", 40);

        assertThat(observe(NAS, "/", 90).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void stayingInTheSameBand_isSuppressed_notAlerted() {
        observe(NAS, "/", 86);   // alerts, band 85

        RemoteDiskPressureTracker.Verdict verdict = observe(NAS, "/", 89);

        assertThat(verdict.outcome()).isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
        // The watcher logs this so a latched-silent disk is diagnosable without reading source.
        assertThat(verdict.notifiedBand()).contains(DiskPressureBand.of(85));
    }

    @Test
    void climbingIntoAHigherBand_alertsAgain() {
        // 80 → 85 → 90 → 95: the disk getting materially worse is worth a second email. A timer is not.
        assertThat(observe(NAS, "/", 81).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 86).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 91).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 96).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void slippingBackToALowerBandWhileStillInPressure_neverReAlerts() {
        // A disk oscillating either side of a band edge (91 → 89 → 91) must not page on every wobble. It
        // never left pressure, so nothing new has happened. Only a genuine recovery resets the ladder.
        observe(NAS, "/", 91);   // alerts at band 90

        assertThat(observe(NAS, "/", 89).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
        assertThat(observe(NAS, "/", 91).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void droppingBelowThreshold_recovers() {
        observe(NAS, "/", 90);

        assertThat(observe(NAS, "/", 40).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.RECOVERED);
    }

    @Test
    void recoveringThenRefilling_alertsAgain() {
        observe(NAS, "/", 90);    // alerts at band 90
        observe(NAS, "/", 40);   // recovers — the ladder is reset

        assertThat(observe(NAS, "/", 86).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void stayingBelowThreshold_saysNothingTwice() {
        observe(NAS, "/", 40);

        assertThat(observe(NAS, "/", 41).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.QUIET);
    }

    @Test
    void theStateOutlivesTheTrackerInstance() {
        // The whole point of the port: the operator deploys several times a day, and every restart used to
        // wipe the in-memory latch. A fresh tracker over the same store must not re-alert.
        observe(NAS, "/", 90);

        tracker = new RemoteDiskPressureTracker(store);

        assertThat(observe(NAS, "/", 90).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void tracksEachFilesystemOfOneMachineIndependently() {
        // #325. On the NAS, / sits permanently above the threshold (the 2.3 GB DSM system partition, 88% by
        // design), so a machine-keyed tracker would swallow /volume1's crossing as "no change".
        observe(NAS, "/", 88);

        assertThat(observe(NAS, "/volume1", 91).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 88).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void twoMachinesSharingAName_areStillTwoMachines() {
        // lan-servers.yml really does hold two machines both called "Printer". Keyed on the name they shared
        // one tracker slot and swallowed each other's transitions; a rename silently reset the state too.
        // Keyed on machine id, neither can happen.
        MachineId printerOne = MachineId.generate();
        MachineId printerTwo = MachineId.generate();

        assertThat(observe(printerOne, reading("Printer", "/", 91), THRESHOLD).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(printerTwo, reading("Printer", "/", 91), THRESHOLD).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    // --- hysteresis on the way down: dipping under the line is not a recovery --------------------------

    @Test
    void aWeekOfSawtoothingAcrossItsThreshold_alertsOnce_andNeverRecovers() {
        // This host, every single day: a Docker build takes ~1.2 GiB and pushes / over its 80% threshold,
        // and the nightly docker-builder-prune.timer gives it back. Clearing the state on any dip under the
        // line made that an alert-and-recovery pair a day about a disk that never changed state.
        List<RemoteDiskPressureTracker.Outcome> week = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            week.add(observe(NAS, "/", 78).outcome());
            week.add(observe(NAS, "/", 82).outcome());
        }

        assertThat(week).filteredOn(RemoteDiskPressureTracker.Outcome.ALERT::equals).hasSize(1);
        assertThat(week).doesNotContain(RemoteDiskPressureTracker.Outcome.RECOVERED);
    }

    @Test
    void dippingOnlyIntoTheRecoveryMargin_neitherRecovers_norReAlertsOnTheWayBackUp() {
        observe(NAS, "/", 82);   // alerts at band 80

        assertThat(observe(NAS, "/", 78).outcome())
            .isNotEqualTo(RemoteDiskPressureTracker.Outcome.RECOVERED);
        assertThat(observe(NAS, "/", 82).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void aFilesystemInTheRecoveryMargin_isStillInPressure_atTheBandItWasNotifiedAt() {
        observe(NAS, "/", 82);   // alerts at band 80

        RemoteDiskPressureTracker.Verdict verdict = observe(NAS, "/", 78);

        assertThat(verdict.outcome()).isEqualTo(RemoteDiskPressureTracker.Outcome.EASING);
        // The watcher logs this, so "waiting to clear" is never indistinguishable from "nobody is watching".
        assertThat(verdict.notifiedBand()).contains(DiskPressureBand.of(80));
    }

    @Test
    void drainingClearOfTheMargin_recoversOnce_andARefillAlertsAgain() {
        observe(NAS, "/", 82);   // alerts at band 80

        assertThat(observe(NAS, "/", 60).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.RECOVERED);
        assertThat(observe(NAS, "/", 60).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.QUIET);
        assertThat(observe(NAS, "/", 82).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void aThresholdNarrowerThanTheMargin_canStillRecover() {
        // An operator may watch a filesystem at 3%; the margin must not put recovery below empty.
        observe(NAS, "/", 5, 3);

        assertThat(observe(NAS, "/", 0, 3).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.RECOVERED);
    }

    @Test
    void oneMountPointOnTwoMachines_isTwoFilesystems() {
        observe(NAS, "/", 91);

        assertThat(observe(NUC, "/", 91).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }
}
