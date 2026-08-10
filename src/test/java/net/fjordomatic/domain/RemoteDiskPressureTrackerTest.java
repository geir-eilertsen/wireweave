package net.fjordomatic.domain;

import net.fjordomatic.adapter.driven.InMemoryDiskPressureStateAdapter;
import net.fjordomatic.domain.port.ForPersistingDiskPressureState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private RemoteDiskPressureTracker.Verdict observe(MachineId machineId, String mountPoint,
                                                      int usedPercent, boolean breaching) {
        return tracker.observe(machineId, reading("nas", mountPoint, usedPercent), breaching);
    }

    @Test
    void aFilesystemAlreadyAboveThresholdOnItsVeryFirstObservation_alertsImmediately() {
        // THE BUG. The Fjord host's root filesystem was at 89% — above the threshold — before Fjord had ever
        // observed it. The old tracker treated a first observation as a silent baseline, so an
        // already-breaching filesystem could never produce a crossing and never alerted. With the state
        // persisted, "first ever" now genuinely means first ever, so the first observation must speak.
        assertThat(observe(NAS, "/", 89, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void aFilesystemBelowThresholdOnItsFirstObservation_saysNothing() {
        assertThat(observe(NAS, "/", 40, false).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.QUIET);
    }

    @Test
    void crossingIntoPressure_alerts() {
        observe(NAS, "/", 40, false);

        assertThat(observe(NAS, "/", 90, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void stayingInTheSameBand_isSuppressed_notAlerted() {
        observe(NAS, "/", 86, true);   // alerts, band 85

        RemoteDiskPressureTracker.Verdict verdict = observe(NAS, "/", 89, true);

        assertThat(verdict.outcome()).isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
        // The watcher logs this so a latched-silent disk is diagnosable without reading source.
        assertThat(verdict.notifiedBand()).contains(DiskPressureBand.of(85));
    }

    @Test
    void climbingIntoAHigherBand_alertsAgain() {
        // 80 → 85 → 90 → 95: the disk getting materially worse is worth a second email. A timer is not.
        assertThat(observe(NAS, "/", 81, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 86, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 91, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 96, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void slippingBackToALowerBandWhileStillInPressure_neverReAlerts() {
        // A disk oscillating either side of a band edge (91 → 89 → 91) must not page on every wobble. It
        // never left pressure, so nothing new has happened. Only a genuine recovery resets the ladder.
        observe(NAS, "/", 91, true);   // alerts at band 90

        assertThat(observe(NAS, "/", 89, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
        assertThat(observe(NAS, "/", 91, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void droppingBelowThreshold_recovers() {
        observe(NAS, "/", 90, true);

        assertThat(observe(NAS, "/", 40, false).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.RECOVERED);
    }

    @Test
    void recoveringThenRefilling_alertsAgain() {
        observe(NAS, "/", 90, true);    // alerts at band 90
        observe(NAS, "/", 40, false);   // recovers — the ladder is reset

        assertThat(observe(NAS, "/", 86, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void stayingBelowThreshold_saysNothingTwice() {
        observe(NAS, "/", 40, false);

        assertThat(observe(NAS, "/", 41, false).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.QUIET);
    }

    @Test
    void theStateOutlivesTheTrackerInstance() {
        // The whole point of the port: the operator deploys several times a day, and every restart used to
        // wipe the in-memory latch. A fresh tracker over the same store must not re-alert.
        observe(NAS, "/", 90, true);

        RemoteDiskPressureTracker afterRestart = new RemoteDiskPressureTracker(store);

        assertThat(afterRestart.observe(NAS, reading("nas", "/", 90), true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void tracksEachFilesystemOfOneMachineIndependently() {
        // #325. On the NAS, / sits permanently above the threshold (the 2.3 GB DSM system partition, 88% by
        // design), so a machine-keyed tracker would swallow /volume1's crossing as "no change".
        observe(NAS, "/", 88, true);

        assertThat(observe(NAS, "/volume1", 91, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(observe(NAS, "/", 88, true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.SUPPRESSED);
    }

    @Test
    void twoMachinesSharingAName_areStillTwoMachines() {
        // lan-servers.yml really does hold two machines both called "Printer". Keyed on the name they shared
        // one tracker slot and swallowed each other's transitions; a rename silently reset the state too.
        // Keyed on machine id, neither can happen.
        MachineId printerOne = MachineId.generate();
        MachineId printerTwo = MachineId.generate();

        assertThat(tracker.observe(printerOne, reading("Printer", "/", 91), true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
        assertThat(tracker.observe(printerTwo, reading("Printer", "/", 91), true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }

    @Test
    void oneMountPointOnTwoMachines_isTwoFilesystems() {
        observe(NAS, "/", 91, true);

        assertThat(tracker.observe(NUC, reading("nuc", "/", 91), true).outcome())
            .isEqualTo(RemoteDiskPressureTracker.Outcome.ALERT);
    }
}
