package net.vaier.adapter.driven;

import net.vaier.domain.DiskFillSample;
import net.vaier.domain.DiskFillTrend;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DiskFillTrendFileAdapterTest {

    private static final MachineId NAS = TestMachineIds.of("NAS");
    private static final MachineId VAIER = TestMachineIds.of("vaier");
    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir
    Path configDir;

    private DiskFillTrendFileAdapter adapter() {
        return new DiskFillTrendFileAdapter(configDir.toString());
    }

    private static DiskFillTrend trendOf(MachineId machineId, String mountPoint, int hours, boolean warned) {
        DiskFillTrend trend = DiskFillTrend.startFor(machineId, mountPoint);
        for (int hour = 0; hour < hours; hour++) {
            trend = trend.observing(T0.plus(Duration.ofHours(hour)), 12_000_000L - hour * 1000L);
        }
        return trend.withWarned(warned);
    }

    @Test
    void anAbsentFile_isTheHealthyFirstBootState_notAnError() {
        assertThat(adapter().find(NAS, "/")).isEmpty();
    }

    @Test
    void theWeekOfSamplesSurvivesARedeploy() {
        // The reason this port exists: the trend used to be a field on the scheduled watcher, so a week of
        // baseline could never accumulate on a box that redeploys several times a day.
        adapter().save(trendOf(VAIER, "/", 48, false));

        assertThat(adapter().find(VAIER, "/")).hasValueSatisfying(trend -> {
            assertThat(trend.samples()).hasSize(48);
            assertThat(trend.samples().get(0)).isEqualTo(new DiskFillSample(T0, 12_000_000L));
        });
    }

    @Test
    void theAlreadyWarnedLatchSurvivesTooSoARedeployDoesNotRePage() {
        adapter().save(trendOf(VAIER, "/", 48, true));

        assertThat(adapter().find(VAIER, "/")).hasValueSatisfying(trend ->
            assertThat(trend.warned()).isTrue());
    }

    @Test
    void save_replacesTheTrendForTheSameFilesystem_notAppendsToIt() {
        DiskFillTrendFileAdapter adapter = adapter();
        adapter.save(trendOf(NAS, "/volume1", 10, false));
        adapter.save(trendOf(NAS, "/volume1", 30, false));

        assertThat(adapter.find(NAS, "/volume1")).hasValueSatisfying(trend ->
            assertThat(trend.samples()).hasSize(30));
    }

    @Test
    void twoFilesystemsOnOneMachine_areStoredSeparately() {
        DiskFillTrendFileAdapter adapter = adapter();
        adapter.save(trendOf(NAS, "/", 10, false));
        adapter.save(trendOf(NAS, "/volume1", 30, true));

        assertThat(adapter.find(NAS, "/")).hasValueSatisfying(trend -> {
            assertThat(trend.samples()).hasSize(10);
            assertThat(trend.warned()).isFalse();
        });
        assertThat(adapter.find(NAS, "/volume1")).hasValueSatisfying(trend ->
            assertThat(trend.warned()).isTrue());
    }

    @Test
    void retainOnly_forgetsADeletedMachine_andKeepsTheRest() {
        DiskFillTrendFileAdapter adapter = adapter();
        adapter.save(trendOf(NAS, "/volume1", 10, false));
        adapter.save(trendOf(VAIER, "/", 10, false));

        adapter.retainOnly(Set.of(VAIER));

        assertThat(adapter().find(NAS, "/volume1")).isEmpty();
        assertThat(adapter().find(VAIER, "/")).isPresent();
    }

    @Test
    void anUnusableEntry_isSkipped_ratherThanAbortingTheWholeLoad() throws Exception {
        Files.writeString(configDir.resolve("disk-fill-trend.yml"), """
            trends:
            - mountPoint: /nameless
              samples: []
            - machineId: not-a-uuid
              mountPoint: /volume2
              samples: []
            - machineId: %s
              mountPoint: /volume1
              warned: true
              samples:
              - at: not-a-timestamp
                availableKb: 100
              - at: '2026-08-01T00:00:00Z'
                availableKb: 900
            """.formatted(NAS.value()));

        DiskFillTrendFileAdapter adapter = adapter();

        assertThat(adapter.find(NAS, "/volume2")).isEmpty();
        assertThat(adapter.find(NAS, "/volume1")).hasValueSatisfying(trend -> {
            assertThat(trend.warned()).isTrue();
            assertThat(trend.samples()).containsExactly(new DiskFillSample(T0, 900L));
        });
    }

    @Test
    void aMalformedFile_isNotAnError_itJustMeansNoTrendYet() throws Exception {
        Files.writeString(configDir.resolve("disk-fill-trend.yml"), "\t: not: yaml: at: all\n[");

        assertThat(adapter().find(NAS, "/")).isEmpty();
    }

    @Test
    void aWeekOfHourlySamplesForTheWholeFleet_staysASmallFile() throws Exception {
        DiskFillTrendFileAdapter adapter = adapter();
        for (int machine = 0; machine < 10; machine++) {
            adapter.save(trendOf(MachineId.generate(), "/volume" + machine, 168, false));
        }

        assertThat(Files.size(configDir.resolve("disk-fill-trend.yml"))).isLessThan(500_000L);
    }

    @Test
    void samplesRoundTripInOrder() {
        adapter().save(new DiskFillTrend(VAIER, "/", List.of(
            new DiskFillSample(T0, 300L),
            new DiskFillSample(T0.plus(Duration.ofHours(1)), 200L),
            new DiskFillSample(T0.plus(Duration.ofHours(2)), 100L)), false));

        assertThat(adapter().find(VAIER, "/")).hasValueSatisfying(trend ->
            assertThat(trend.samples()).extracting(DiskFillSample::availableKb)
                .containsExactly(300L, 200L, 100L));
    }
}
