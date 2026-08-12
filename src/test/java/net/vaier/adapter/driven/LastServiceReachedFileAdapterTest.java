package net.vaier.adapter.driven;

import net.vaier.domain.LastServiceReached;
import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LastServiceReachedFileAdapterTest {

    private static final Instant NOON = Instant.parse("2026-08-11T12:00:00Z");
    private static final MachineId PHONE = TestMachineIds.of("phone");
    private static final MachineId LAPTOP = TestMachineIds.of("laptop");

    @TempDir
    Path configDir;

    private LastServiceReachedFileAdapter adapter() {
        return new LastServiceReachedFileAdapter(configDir.toString());
    }

    /** Nobody has reached anything yet. That is the healthy first boot, not an error. */
    @Test
    void getAll_withNoFileYet_isEmpty() {
        assertThat(adapter().getAll().entries()).isEmpty();
    }

    @Test
    void save_thenFlush_thenReadBackByAFreshStore() {
        LastServiceReachedFileAdapter adapter = adapter();
        adapter.save(new LastServiceReached(PHONE, "grafana.example.com", NOON));
        adapter.flush();

        assertThat(adapter().getAll().forMachine(PHONE))
            .contains(new LastServiceReached(PHONE, "grafana.example.com", NOON));
    }

    /**
     * The write rides on the endpoint that authenticates every request to every gated service, so it is
     * held and written once a minute — but the peer view must read what was just recorded, not the file.
     */
    @Test
    void save_isVisibleImmediately_andTouchesNoFileUntilTheFlush() {
        LastServiceReachedFileAdapter adapter = adapter();

        adapter.save(new LastServiceReached(PHONE, "grafana.example.com", NOON));

        assertThat(adapter.getAll().forMachine(PHONE)).isPresent();
        assertThat(Files.exists(configDir.resolve("last-services-reached.yml"))).isFalse();
    }

    /** An idle minute must not rewrite the file — the flush runs once a minute forever. */
    @Test
    void flush_withNothingRecorded_writesNothing() {
        adapter().flush();

        assertThat(Files.exists(configDir.resolve("last-services-reached.yml"))).isFalse();
    }

    @Test
    void save_keepsEveryMachineThatHasReachedSomething() {
        LastServiceReachedFileAdapter adapter = adapter();
        adapter.save(new LastServiceReached(PHONE, "grafana.example.com", NOON));
        adapter.save(new LastServiceReached(LAPTOP, "plex.example.com", NOON.plusSeconds(30)));
        adapter.flush();

        assertThat(adapter().getAll().entries()).hasSize(2);
    }

    /** The merge is the domain's rule, and the store must not lose it across a restart. */
    @Test
    void save_replacesThatMachinesEarlierReach() {
        LastServiceReachedFileAdapter adapter = adapter();
        adapter.save(new LastServiceReached(PHONE, "grafana.example.com", NOON));
        adapter.save(new LastServiceReached(PHONE, "plex.example.com", NOON.plusSeconds(60)));
        adapter.flush();

        assertThat(adapter().getAll().entries()).singleElement()
            .extracting(LastServiceReached::host).isEqualTo("plex.example.com");
    }

    /** A file written before this boot is what a restart reads back; a later reach merges into it. */
    @Test
    void save_mergesIntoWhatWasAlreadyOnDisk() throws Exception {
        Files.writeString(configDir.resolve("last-services-reached.yml"), """
            reached:
            - machineId: %s
              host: grafana.example.com
              at: '2026-08-11T12:00:00Z'
            """.formatted(PHONE.value()));

        LastServiceReachedFileAdapter adapter = adapter();
        adapter.save(new LastServiceReached(LAPTOP, "plex.example.com", NOON));
        adapter.flush();

        assertThat(adapter().getAll().entries()).hasSize(2);
    }

    /** An entry that will not read costs one machine's row, never every other machine's. */
    @Test
    void getAll_skipsAnUnreadableEntryAndKeepsTheRest() throws Exception {
        Files.writeString(configDir.resolve("last-services-reached.yml"), """
            reached:
            - machineId: not-a-machine-id
              host: broken.example.com
              at: '2026-08-11T12:00:00Z'
            - machineId: %s
              host: grafana.example.com
              at: '2026-08-11T12:00:00Z'
            """.formatted(PHONE.value()));

        assertThat(adapter().getAll().entries()).singleElement()
            .extracting(LastServiceReached::host).isEqualTo("grafana.example.com");
    }

    @Test
    void getAll_withAnUnreadableFile_isEmpty() throws Exception {
        Files.writeString(configDir.resolve("last-services-reached.yml"), "\t: not: yaml: at all\n  - [");

        assertThat(adapter().getAll().entries()).isEmpty();
    }
}
