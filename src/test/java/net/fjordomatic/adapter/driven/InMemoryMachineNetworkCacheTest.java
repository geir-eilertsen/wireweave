package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineNetworks;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMachineNetworkCacheTest {

    private final InMemoryMachineNetworkCache cache = new InMemoryMachineNetworkCache();

    private static MachineNetworks reading(String cidrAddress) {
        return MachineNetworks.parse("2: eth0    inet " + cidrAddress + " scope global eth0\n"
            + "default via 192.168.1.1 dev eth0\n");
    }

    @Test
    void aMachineNeverRead_isUnknownRatherThanNull() {
        assertThat(cache.getNetworks(MachineId.generate()).lanCandidate()).isEmpty();
    }

    @Test
    void recordsAndServesTheLastReading() {
        MachineId id = MachineId.generate();
        cache.record(id, reading("192.168.1.10/24"));

        assertThat(cache.getNetworks(id).lanCandidate())
            .hasValueSatisfying(n -> assertThat(n.cidr()).isEqualTo("192.168.1.0/24"));
    }

    @Test
    void aLaterReadingReplacesTheEarlierOne() {
        MachineId id = MachineId.generate();
        cache.record(id, reading("192.168.1.10/24"));
        cache.record(id, reading("10.20.0.5/16"));

        assertThat(cache.getNetworks(id).lanCandidate())
            .hasValueSatisfying(n -> assertThat(n.cidr()).isEqualTo("10.20.0.0/16"));
    }

    @Test
    void retainOnly_forgetsAMachineTheFleetNoLongerHas() {
        MachineId kept = MachineId.generate();
        MachineId deleted = MachineId.generate();
        cache.record(kept, reading("192.168.1.10/24"));
        cache.record(deleted, reading("192.168.3.10/24"));

        cache.retainOnly(Set.of(kept));

        assertThat(cache.getNetworks(kept).lanCandidate()).isPresent();
        assertThat(cache.getNetworks(deleted).lanCandidate()).isEmpty();
    }
}
