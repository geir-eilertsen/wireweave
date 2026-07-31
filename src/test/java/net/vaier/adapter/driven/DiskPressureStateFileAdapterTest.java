package net.vaier.adapter.driven;

import net.vaier.domain.DiskPressureBand;
import net.vaier.domain.DiskPressureState;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DiskPressureStateFileAdapterTest {

    private static final MachineId NAS = TestMachineIds.of("NAS");
    private static final MachineId VAIER = TestMachineIds.of("vaier");

    @TempDir
    Path configDir;

    private DiskPressureStateFileAdapter adapter() {
        return new DiskPressureStateFileAdapter(configDir.toString());
    }

    @Test
    void anAbsentFile_isTheHealthyFirstBootState_notAnError() {
        assertThat(adapter().find(NAS, "/")).isEmpty();
    }

    @Test
    void save_thenFind_roundTripsTheBand() {
        adapter().save(new DiskPressureState(NAS, "/volume1", DiskPressureBand.of(91)));

        assertThat(adapter().find(NAS, "/volume1"))
            .contains(new DiskPressureState(NAS, "/volume1", DiskPressureBand.of(90)));
    }

    @Test
    void theStateSurvivesARestart() {
        // The reason this port exists at all: the operator deploys several times a day and every restart
        // used to wipe the in-memory latch, so a disk that was already full alerted exactly never.
        adapter().save(new DiskPressureState(VAIER, "/", DiskPressureBand.of(89)));

        assertThat(adapter().find(VAIER, "/")).isPresent();
    }

    @Test
    void save_replacesTheStateForTheSameFilesystem_notAppendsToIt() {
        DiskPressureStateFileAdapter adapter = adapter();
        adapter.save(new DiskPressureState(NAS, "/volume1", DiskPressureBand.of(86)));
        adapter.save(new DiskPressureState(NAS, "/volume1", DiskPressureBand.of(91)));

        assertThat(adapter.find(NAS, "/volume1"))
            .map(DiskPressureState::notifiedBand).contains(DiskPressureBand.of(90));
    }

    @Test
    void twoFilesystemsOnOneMachine_areStoredSeparately() {
        DiskPressureStateFileAdapter adapter = adapter();
        adapter.save(new DiskPressureState(NAS, "/", DiskPressureBand.of(88)));
        adapter.save(new DiskPressureState(NAS, "/volume1", DiskPressureBand.of(91)));

        assertThat(adapter.find(NAS, "/")).map(DiskPressureState::notifiedBand)
            .contains(DiskPressureBand.of(85));
        assertThat(adapter.find(NAS, "/volume1")).map(DiskPressureState::notifiedBand)
            .contains(DiskPressureBand.of(90));
    }

    @Test
    void clear_forgetsOnlyThatFilesystem() {
        DiskPressureStateFileAdapter adapter = adapter();
        adapter.save(new DiskPressureState(NAS, "/", DiskPressureBand.of(88)));
        adapter.save(new DiskPressureState(NAS, "/volume1", DiskPressureBand.of(91)));

        adapter.clear(NAS, "/");

        assertThat(adapter.find(NAS, "/")).isEmpty();
        assertThat(adapter.find(NAS, "/volume1")).isPresent();
    }

    @Test
    void clear_isPersisted_soARecoveredDiskAlertsAgainAfterARestart() {
        adapter().save(new DiskPressureState(NAS, "/", DiskPressureBand.of(91)));

        adapter().clear(NAS, "/");

        assertThat(adapter().find(NAS, "/")).isEmpty();
    }

    @Test
    void anUnusableEntry_isSkipped_ratherThanAbortingTheWholeLoad() throws Exception {
        // Tolerant on load like the other file adapters: one bad entry must never silence every other
        // filesystem's escalation state — losing state means re-alerting, which is the safe direction.
        Files.writeString(configDir.resolve("disk-pressure.yml"), """
            pressure:
            - mountPoint: /
              notifiedBandPercent: 85
            - machineId: not-a-uuid
              mountPoint: /volume2
              notifiedBandPercent: 85
            - machineId: %s
              mountPoint: /volume1
              notifiedBandPercent: 87
            - machineId: %s
              mountPoint: /volume3
              notifiedBandPercent: 90
            """.formatted(NAS.value(), NAS.value()));

        DiskPressureStateFileAdapter adapter = adapter();

        assertThat(adapter.find(NAS, "/volume1")).isEmpty();   // 87 is not a band any code could produce
        assertThat(adapter.find(NAS, "/volume2")).isEmpty();
        assertThat(adapter.find(NAS, "/volume3")).map(DiskPressureState::notifiedBand)
            .contains(DiskPressureBand.of(90));
    }

    @Test
    void aMalformedFile_isNotAnError_itJustMeansNoStateYet() throws Exception {
        Files.writeString(configDir.resolve("disk-pressure.yml"), "\t: not: yaml: at: all\n[");

        assertThat(adapter().find(NAS, "/")).isEmpty();
    }
}
