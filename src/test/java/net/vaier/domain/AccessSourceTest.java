package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessSourceTest {

    private static final Instant NOON = Instant.parse("2026-08-09T12:00:00Z");
    private static final GeoLocation OSLO = new GeoLocation(59.91, 10.75, "Oslo", "Norway");

    // --- locatable() ---

    @Test
    void isLocatableWhenBothCoordinatesArePresent() {
        assertThat(AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON).locatable()).isTrue();
    }

    @Test
    void isNotLocatableWithoutCoordinates() {
        assertThat(AccessSource.firstAccessFrom(null, "geir@example.com", NOON).locatable()).isFalse();
    }

    /**
     * Null island is a patch of Atlantic off Ghana, not a place anybody signed in from — the same
     * carve-out {@code BlockDecision.locatable()} makes, and for the same reason: a marker there is a
     * lie, no marker is merely a gap.
     */
    @Test
    void isNotLocatableAtNullIsland() {
        assertThat(source(0.0, 0.0).locatable()).isFalse();
    }

    /** A genuine zero on one axis alone is a real place — Quito sits on the equator. */
    @Test
    void isLocatableWithAGenuineZeroOnOneAxisOnly() {
        assertThat(source(0.0, -78.5).locatable()).isTrue();
        assertThat(source(59.91, 0.0).locatable()).isTrue();
    }

    // --- withAccess() ---

    @Test
    void countsEachAccessAndMovesTheLastSeen() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON)
            .withAccess("geir@example.com", NOON.plusSeconds(60));

        assertThat(source.count()).isEqualTo(2);
        assertThat(source.firstSeen()).isEqualTo(NOON);
        assertThat(source.lastSeen()).isEqualTo(NOON.plusSeconds(60));
    }

    @Test
    void keepsTheSameOnePersonOnceHoweverOftenTheyReturn() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON)
            .withAccess("geir@example.com", NOON.plusSeconds(1))
            .withAccess("geir@example.com", NOON.plusSeconds(2));

        assertThat(source.people()).containsExactly("geir@example.com");
        assertThat(source.count()).isEqualTo(3);
    }

    @Test
    void namesEveryoneAllowedFromThePlace() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON)
            .withAccess("kari@example.com", NOON.plusSeconds(1));

        assertThat(source.people()).containsExactlyInAnyOrder("geir@example.com", "kari@example.com");
    }

    /**
     * The file has to stay bounded. A shared office IP could otherwise accumulate every identity that has
     * ever signed in from it, and the store would grow without limit — so the set is capped, and it is the
     * most recent people that are worth keeping.
     */
    @Test
    void capsThePeopleAtTwentyKeepingTheMostRecent() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "p0@example.com", NOON);
        for (int i = 1; i <= 25; i++) {
            source = source.withAccess("p" + i + "@example.com", NOON.plusSeconds(i));
        }

        assertThat(source.people()).hasSize(AccessSource.MAX_PEOPLE);
        assertThat(source.people()).contains("p25@example.com").doesNotContain("p0@example.com");
        assertThat(source.count()).isEqualTo(26);
    }

    @Test
    void stillCountsAnAccessWithNoPersonToName() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, null, NOON);

        assertThat(source.count()).isEqualTo(1);
        assertThat(source.people()).isEmpty();
    }

    /** Clock skew between hops must never drag the most recent access backwards. */
    @Test
    void neverMovesTheLastSeenBackwards() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON)
            .withAccess("geir@example.com", NOON.minusSeconds(600));

        assertThat(source.lastSeen()).isEqualTo(NOON);
    }

    // --- isStale() ---

    @Test
    void isStaleOnceItsLastAccessIsOlderThanAMonth() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON);

        assertThat(source.isStale(NOON.plus(AccessSource.RETENTION).plusSeconds(1))).isTrue();
        assertThat(source.isStale(NOON.plus(AccessSource.RETENTION))).isFalse();
        assertThat(source.isStale(NOON.plusSeconds(3600))).isFalse();
    }

    // --- isSamePlaceAs() ---

    @Test
    void isTheSamePlaceAsAnotherAccessFromTheSameCity() {
        AccessSource source = AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON);

        assertThat(source.isSamePlaceAs(new GeoLocation(59.92, 10.76, "Oslo", "Norway"))).isTrue();
        assertThat(source.isSamePlaceAs(new GeoLocation(63.43, 10.39, "Trondheim", "Norway"))).isFalse();
    }

    @Test
    void aPlacedSourceIsNeverTheSamePlaceAsAnUnplaceableAccess() {
        assertThat(AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON).isSamePlaceAs(null))
            .isFalse();
    }

    @Test
    void theUnplaceableSourceIsTheSamePlaceAsAnyOtherUnplaceableAccess() {
        AccessSource unplaceable = AccessSource.firstAccessFrom(null, "geir@example.com", NOON);

        assertThat(unplaceable.isSamePlaceAs(null)).isTrue();
        assertThat(unplaceable.isSamePlaceAs(OSLO)).isFalse();
    }

    /** A coordinate the database knows but has no city name for is still a real place, not the bucket. */
    @Test
    void coordinatesWithoutACityNameAreStillAPlaceOfTheirOwn() {
        GeoLocation nameless = new GeoLocation(59.91, 10.75, null, null);
        AccessSource source = AccessSource.firstAccessFrom(nameless, "geir@example.com", NOON);

        assertThat(source.isSamePlaceAs(nameless)).isTrue();
        assertThat(source.isSamePlaceAs(null)).isFalse();
        assertThat(source.locatable()).isTrue();
    }

    /**
     * Two places the database placed but could not name are not one place just because neither has a name.
     * Comparing city and country alone made an unnamed Oslo equal to an unnamed Singapore: they merged into
     * one dot, drawn at whichever arrived first. A marker in the wrong place is the same lie the null-island
     * carve-out exists to prevent.
     */
    @Test
    void twoPlacesNeitherOfWhichTheDatabaseCanNameAreStillTwoPlaces() {
        GeoLocation namelessOslo = new GeoLocation(59.91, 10.75, null, null);
        GeoLocation namelessSingapore = new GeoLocation(1.35, 103.82, null, null);
        AccessSource source = AccessSource.firstAccessFrom(namelessOslo, "geir@example.com", NOON);

        assertThat(source.isSamePlaceAs(namelessOslo)).isTrue();
        assertThat(source.isSamePlaceAs(namelessSingapore)).isFalse();
    }

    // --- place() ---

    @Test
    void readsAsCityAndCountryToAPerson() {
        assertThat(AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON).place())
            .isEqualTo("Oslo, Norway");
    }

    @Test
    void readsAsWhicheverHalfIsKnown() {
        assertThat(AccessSource.firstAccessFrom(new GeoLocation(59.91, 10.75, null, "Norway"),
            "geir@example.com", NOON).place()).isEqualTo("Norway");
        assertThat(AccessSource.firstAccessFrom(new GeoLocation(59.91, 10.75, "Oslo", null),
            "geir@example.com", NOON).place()).isEqualTo("Oslo");
    }

    @Test
    void saysSoWhenThereIsNoPlaceToName() {
        assertThat(AccessSource.firstAccessFrom(null, "geir@example.com", NOON).place())
            .isEqualTo("Not placeable");
    }

    /**
     * "Not placeable" is the unplaceable bucket's label, and a source {@link AccessSource#locatable()} has
     * just called drawable is about to get a marker. Captioning that marker as being nowhere contradicts the
     * dot it sits on, so a place the database placed but could not name says where it is.
     */
    @Test
    void aPlaceThatCanBeDrawnIsNeverCaptionedAsNowhere() {
        AccessSource nameless = AccessSource.firstAccessFrom(
            new GeoLocation(59.91, 10.75, null, null), "geir@example.com", NOON);

        assertThat(nameless.locatable()).isTrue();
        assertThat(nameless.place()).isEqualTo("Unnamed place at 59.91, 10.75");
    }

    /** A named place still reads by its name — the coordinates are the fallback, never the preference. */
    @Test
    void aNamedPlaceIsNeverReducedToItsCoordinates() {
        assertThat(AccessSource.firstAccessFrom(OSLO, "geir@example.com", NOON).place())
            .isEqualTo("Oslo, Norway");
    }

    // --- the invariants no caller may break ---

    /**
     * The four location fields are absent together or the pair of coordinates is present together, and
     * nothing else. Half a coordinate reconstituted from an older file — or from a hand-edited one — would
     * either become null island on load or draw a marker at a longitude of zero, both of which are lies.
     * Enforced in the canonical constructor rather than in the factory, because a record's constructor is
     * as public as the record and the builder goes straight through it.
     */
    @Test
    void refusesHalfACoordinate() {
        assertThatThrownBy(() -> AccessSource.builder()
            .city("Oslo").country("Norway").latitude(59.91)
            .count(1).firstSeen(NOON).lastSeen(NOON).people(List.of()).build())
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AccessSource.builder()
            .city("Oslo").country("Norway").longitude(10.75)
            .count(1).firstSeen(NOON).lastSeen(NOON).people(List.of()).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** A place nobody has come from is not a place. It would draw a dot standing for nothing. */
    @Test
    void refusesASourceNobodyHasBeenSeenFrom() {
        assertThatThrownBy(() -> AccessSource.builder()
            .city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
            .count(0).firstSeen(NOON).lastSeen(NOON).people(List.of()).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesASourceWithNoTimestamps() {
        assertThatThrownBy(() -> AccessSource.builder()
            .city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
            .count(1).lastSeen(NOON).people(List.of()).build())
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> AccessSource.builder()
            .city("Oslo").country("Norway").latitude(59.91).longitude(10.75)
            .count(1).firstSeen(NOON).people(List.of()).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** A named place with no coordinates the database could give is still readable, just not drawable. */
    @Test
    void acceptsANamedPlaceWithNoCoordinates() {
        AccessSource named = AccessSource.builder()
            .city("Oslo").country("Norway")
            .count(1).firstSeen(NOON).lastSeen(NOON).people(List.of()).build();

        assertThat(named.locatable()).isFalse();
        assertThat(named.place()).isEqualTo("Oslo, Norway");
    }

    private static AccessSource source(double latitude, double longitude) {
        return AccessSource.builder()
            .city("Somewhere").country("Nowhere")
            .latitude(latitude).longitude(longitude)
            .count(1).firstSeen(NOON).lastSeen(NOON)
            .people(List.of())
            .build();
    }
}
