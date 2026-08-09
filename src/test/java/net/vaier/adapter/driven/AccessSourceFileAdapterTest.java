package net.vaier.adapter.driven;

import net.vaier.domain.AccessSource;
import net.vaier.domain.AccessSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessSourceFileAdapterTest {

    private static final Instant NOON = Instant.parse("2026-08-09T12:00:00Z");

    @TempDir
    Path configDir;

    private AccessSourceFileAdapter adapter() {
        return new AccessSourceFileAdapter(configDir.toString());
    }

    /** Nobody has been let in yet. That is the healthy first boot, not an error. */
    @Test
    void getAll_withNoFileYet_isEmpty() {
        assertThat(adapter().getAll().sources()).isEmpty();
    }

    @Test
    void save_thenGetAll_roundTripsAPlacedSource() {
        AccessSource oslo = AccessSource.builder()
            .city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
            .count(412).firstSeen(NOON.minusSeconds(86400)).lastSeen(NOON)
            .people(List.of("geir@example.com", "kari@example.com"))
            .build();

        adapter().save(AccessSources.of(List.of(oslo)));

        assertThat(adapter().getAll().sources()).singleElement().satisfies(source -> {
            assertThat(source.city()).isEqualTo("Oslo");
            assertThat(source.country()).isEqualTo("Norway");
            assertThat(source.latitude()).isEqualTo(59.91);
            assertThat(source.longitude()).isEqualTo(10.75);
            assertThat(source.count()).isEqualTo(412);
            assertThat(source.firstSeen()).isEqualTo(NOON.minusSeconds(86400));
            assertThat(source.lastSeen()).isEqualTo(NOON);
            assertThat(source.people()).containsExactly("geir@example.com", "kari@example.com");
        });
    }

    /** The unplaceable source has to survive the round trip as unplaceable, not as a point at 0/0. */
    @Test
    void save_thenGetAll_roundTripsTheUnplaceableSource() {
        adapter().save(AccessSources.of(List.of(
            AccessSource.firstAccessFrom(null, "geir@example.com", NOON))));

        assertThat(adapter().getAll().sources()).singleElement().satisfies(source -> {
            assertThat(source.isUnplaceable()).isTrue();
            assertThat(source.locatable()).isFalse();
            assertThat(source.latitude()).isNull();
            assertThat(source.longitude()).isNull();
        });
    }

    /** A wholesale rewrite: a pruned source must actually leave the file. */
    @Test
    void save_replacesEverythingThatWasThereBefore() {
        AccessSourceFileAdapter adapter = adapter();
        adapter.save(AccessSources.of(List.of(
            AccessSource.builder().city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
                .count(1).firstSeen(NOON).lastSeen(NOON).people(List.of()).build(),
            AccessSource.builder().city("Bergen").country("Norway").latitude(60.39).longitude(5.32)
                .count(1).firstSeen(NOON).lastSeen(NOON).people(List.of()).build())));

        adapter.save(AccessSources.of(List.of(
            AccessSource.builder().city("Bergen").country("Norway").latitude(60.39).longitude(5.32)
                .count(1).firstSeen(NOON).lastSeen(NOON).people(List.of()).build())));

        assertThat(adapter.getAll().sources()).extracting(AccessSource::city).containsExactly("Bergen");
    }

    /**
     * Tolerant on load, like every other file adapter here: one entry written by an older Vaier, or edited
     * by hand into nonsense, costs its own count and nothing else. Losing every other place because one
     * line is unreadable would be the worse failure.
     */
    @Test
    void getAll_skipsAnUnreadableEntryAndKeepsTheRest() throws Exception {
        Files.writeString(configDir.resolve("access-sources.yml"), """
            sources:
            - city: Nowhere
              country: Nowhere
              count: 3
              lastSeen: 'not-a-timestamp'
            - city: Oslo
              country: Norway
              latitude: 59.91
              longitude: 10.75
              count: 412
              firstSeen: '2026-08-08T12:00:00Z'
              lastSeen: '2026-08-09T12:00:00Z'
              people:
              - geir@example.com
            """);

        assertThat(adapter().getAll().sources()).extracting(AccessSource::city).containsExactly("Oslo");
    }

    /**
     * The absent-together rule on the coordinates is the domain's, and this adapter does not get a vote:
     * it hands the file's contents to {@code AccessSource} and skips whatever the domain refuses. A
     * half-coordinate entry — an older Vaier, a hand edit — would otherwise round-trip into null island.
     */
    @Test
    void getAll_skipsAnEntryTheDomainRefuses() throws Exception {
        Files.writeString(configDir.resolve("access-sources.yml"), """
            sources:
            - city: Oslo
              country: Norway
              latitude: 59.91
              count: 4
              firstSeen: '2026-08-08T12:00:00Z'
              lastSeen: '2026-08-09T12:00:00Z'
            - city: Bergen
              country: Norway
              count: 0
              firstSeen: '2026-08-08T12:00:00Z'
              lastSeen: '2026-08-09T12:00:00Z'
            - city: Trondheim
              country: Norway
              latitude: 63.43
              longitude: 10.39
              count: 7
              firstSeen: '2026-08-08T12:00:00Z'
              lastSeen: '2026-08-09T12:00:00Z'
            """);

        assertThat(adapter().getAll().sources())
            .extracting(AccessSource::city).containsExactly("Trondheim");
    }

    @Test
    void getAll_withAnUnparseableFile_isEmptyRatherThanThrowing() throws Exception {
        Files.writeString(configDir.resolve("access-sources.yml"), "\t: this is not: yaml: at all\n  - [");

        assertThat(adapter().getAll().sources()).isEmpty();
    }

    /** An entry with no people is ordinary — an access whose identity Vaier had no name for. */
    @Test
    void getAll_readsAnEntryWithNoPeopleAsNobodyNamed() throws Exception {
        Files.writeString(configDir.resolve("access-sources.yml"), """
            sources:
            - city: Oslo
              country: Norway
              latitude: 59.91
              longitude: 10.75
              count: 2
              firstSeen: '2026-08-08T12:00:00Z'
              lastSeen: '2026-08-09T12:00:00Z'
            """);

        assertThat(adapter().getAll().sources()).singleElement()
            .satisfies(source -> assertThat(source.people()).isEmpty());
    }
}
