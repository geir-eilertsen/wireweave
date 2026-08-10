package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.DockerCommandAccess;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.TestMachineIds;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryDockerCommandAccessCacheTest {

    private static final MachineId COLINA = TestMachineIds.of("colina27");
    private static final MachineId APALVEIEN = TestMachineIds.of("apalveien5");

    private final InMemoryDockerCommandAccessCache cache = new InMemoryDockerCommandAccessCache();

    @Test
    void aMachineNobodyHasSweptReadsUnknown_whichIsNotARefusal() {
        assertThat(cache.accessFor(COLINA)).isEqualTo(DockerCommandAccess.UNKNOWN);
        assertThat(cache.accessFor(COLINA).refused()).isFalse();
    }

    @Test
    void whatTheSweepRecordedIsWhatTheJudgeReads() {
        cache.record(COLINA, DockerCommandAccess.REFUSED);

        assertThat(cache.accessFor(COLINA)).isEqualTo(DockerCommandAccess.REFUSED);
    }

    @Test
    void aRefusalIsNotSticky_soFixingTheDockerGroupHealsOnTheNextSweep() {
        cache.record(COLINA, DockerCommandAccess.REFUSED);

        cache.record(COLINA, DockerCommandAccess.GRANTED);

        assertThat(cache.accessFor(COLINA)).isEqualTo(DockerCommandAccess.GRANTED);
    }

    @Test
    void machinesAreKeptApart_becauseThisIsAFactAboutOneMachine() {
        cache.record(COLINA, DockerCommandAccess.REFUSED);

        assertThat(cache.accessFor(APALVEIEN)).isEqualTo(DockerCommandAccess.UNKNOWN);
    }

    @Test
    void aMachineThatLeftTheFleetIsForgotten() {
        cache.record(COLINA, DockerCommandAccess.REFUSED);
        cache.record(APALVEIEN, DockerCommandAccess.GRANTED);

        cache.retainOnly(Set.of(APALVEIEN));

        assertThat(cache.accessFor(COLINA)).isEqualTo(DockerCommandAccess.UNKNOWN);
        assertThat(cache.accessFor(APALVEIEN)).isEqualTo(DockerCommandAccess.GRANTED);
    }
}
