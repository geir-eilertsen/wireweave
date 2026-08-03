package net.vaier.adapter.driven;

import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fleet's <b>machine disk standing</b>s, held in memory the way peer stats are: a disk reading is what
 * Vaier last saw, never a record, so nothing is written to disk for it.
 */
class InMemoryMachineDiskStandingCacheTest {

    private static final MachineId NAS = TestMachineIds.of("NAS");
    private static final MachineId ROON = TestMachineIds.of("Roon server");

    private InMemoryMachineDiskStandingCache cache;

    @BeforeEach
    void setUp() {
        cache = new InMemoryMachineDiskStandingCache();
    }

    private static MachineDiskStanding standing(MachineId machineId, int usedPercent) {
        return MachineDiskStanding.builder()
            .machineId(machineId)
            .worstMountPoint("/")
            .worstUsedPercent(usedPercent)
            .worstThresholdPercent(85)
            .breachingFilesystems(usedPercent > 85 ? 1 : 0)
            .watchedFilesystems(1)
            .build();
    }

    @Test
    void aMachineNobodyHasSweptYet_hasNoStanding() {
        assertThat(cache.getAll()).isEmpty();
    }

    @Test
    void recordingAStanding_handsBackWhatWasThereBefore_soOnlyChangesWakeTheFleet() {
        assertThat(cache.record(standing(NAS, 40))).isEmpty();
        assertThat(cache.record(standing(NAS, 91))).contains(standing(NAS, 40));
        assertThat(cache.getAll()).containsExactly(standing(NAS, 91));
    }

    @Test
    void forgettingAMachine_dropsItsStanding_ratherThanLeavingAStaleOneOnTheCard() {
        cache.record(standing(NAS, 91));

        assertThat(cache.forget(NAS)).contains(standing(NAS, 91));
        assertThat(cache.forget(NAS)).isEmpty();
        assertThat(cache.getAll()).isEmpty();
    }

    @Test
    void aMachineThatLeftTheFleet_doesNotKeepItsStandingForever() {
        cache.record(standing(NAS, 91));
        cache.record(standing(ROON, 40));

        cache.retainOnly(Set.of(NAS));

        assertThat(cache.getAll()).containsExactly(standing(NAS, 91));
    }
}
