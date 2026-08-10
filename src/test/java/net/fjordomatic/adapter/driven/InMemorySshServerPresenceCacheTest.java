package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.SshServerPresence;
import net.fjordomatic.domain.TestMachineIds;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemorySshServerPresenceCacheTest {

    private final InMemorySshServerPresenceCache cache = new InMemorySshServerPresenceCache();

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Test
    void unrecordedMachine_readsUnknown() {
        assertThat(cache.getPresence(mid("nas"))).isEqualTo(SshServerPresence.UNKNOWN);
    }

    @Test
    void recordThenRead() {
        cache.record(mid("nas"), SshServerPresence.ABSENT);

        assertThat(cache.getPresence(mid("nas"))).isEqualTo(SshServerPresence.ABSENT);
    }

    @Test
    void recordReturnsThePreviousValue() {
        assertThat(cache.record(mid("nas"), SshServerPresence.ABSENT)).isEqualTo(SshServerPresence.UNKNOWN);
        assertThat(cache.record(mid("nas"), SshServerPresence.PRESENT)).isEqualTo(SshServerPresence.ABSENT);
    }

    @Test
    void retainOnlyDropsUnlistedMachines() {
        cache.record(mid("nas"), SshServerPresence.ABSENT);
        cache.record(mid("printer"), SshServerPresence.PRESENT);

        cache.retainOnly(Set.of(mid("nas")));

        assertThat(cache.getPresence(mid("nas"))).isEqualTo(SshServerPresence.ABSENT);
        assertThat(cache.getPresence(mid("printer"))).isEqualTo(SshServerPresence.UNKNOWN);
    }
}
