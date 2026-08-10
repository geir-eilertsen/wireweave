package net.vaier.domain;

import net.vaier.domain.port.ForGeolocatingIps;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AccessSourcesTest {

    private static final Instant NOON = Instant.parse("2026-08-09T12:00:00Z");
    private static final GeoLocation OSLO = new GeoLocation(59.91, 10.75, "Oslo", "Norway");
    private static final GeoLocation BERGEN = new GeoLocation(60.39, 5.32, "Bergen", "Norway");

    /** The private ranges the geolocation adapter genuinely returns nothing for. */
    private static final ForGeolocatingIps NOWHERE = ip -> Optional.empty();

    private static ForGeolocatingIps geoOf(Map<String, GeoLocation> byIp) {
        return ip -> Optional.ofNullable(byIp.get(ip));
    }

    @Test
    void recordingTheFirstAccessStartsANewSource() {
        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, geoOf(Map.of("203.0.113.7", OSLO)));

        assertThat(sources.sources()).singleElement().satisfies(source -> {
            assertThat(source.city()).isEqualTo("Oslo");
            assertThat(source.country()).isEqualTo("Norway");
            assertThat(source.count()).isEqualTo(1);
            assertThat(source.people()).containsExactly("geir@example.com");
        });
    }

    /** Aggregated per place: two different addresses in the same city are one green dot, not two. */
    @Test
    void recordingASecondAccessFromTheSameCityMergesIntoTheOneSource() {
        ForGeolocatingIps geo = geoOf(Map.of("203.0.113.7", OSLO, "198.51.100.9", OSLO));

        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, geo)
            .recording("198.51.100.9", "kari@example.com", NOON.plusSeconds(30), geo);

        assertThat(sources.sources()).singleElement().satisfies(source -> {
            assertThat(source.count()).isEqualTo(2);
            assertThat(source.people()).containsExactlyInAnyOrder("geir@example.com", "kari@example.com");
            assertThat(source.lastSeen()).isEqualTo(NOON.plusSeconds(30));
        });
    }

    @Test
    void recordingAnAccessFromAnotherCityStartsASecondSource() {
        ForGeolocatingIps geo = geoOf(Map.of("203.0.113.7", OSLO, "198.51.100.9", BERGEN));

        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, geo)
            .recording("198.51.100.9", "geir@example.com", NOON, geo);

        assertThat(sources.sources()).hasSize(2)
            .extracting(AccessSource::city).containsExactlyInAnyOrder("Oslo", "Bergen");
    }

    /**
     * A LAN or VPN address has no place on a map and the geolocation adapter rightly says so. Dropping the
     * access would make the totals a lie, so it lands in the one unplaceable source instead.
     */
    @Test
    void anAccessThatCannotBePlacedIsKeptInOneUnplaceableSource() {
        AccessSources sources = AccessSources.empty()
            .recording("10.13.13.6", "geir@example.com", NOON, NOWHERE)
            .recording("192.168.3.20", "kari@example.com", NOON.plusSeconds(5), NOWHERE);

        assertThat(sources.sources()).singleElement().satisfies(source -> {
            assertThat(source.locatable()).isFalse();
            assertThat(source.city()).isNull();
            assertThat(source.country()).isNull();
            assertThat(source.latitude()).isNull();
            assertThat(source.longitude()).isNull();
            assertThat(source.count()).isEqualTo(2);
            assertThat(source.people()).containsExactlyInAnyOrder("geir@example.com", "kari@example.com");
        });
    }

    @Test
    void theUnplaceableSourceNeverSwallowsAPlacedAccess() {
        ForGeolocatingIps geo = geoOf(Map.of("203.0.113.7", OSLO));

        AccessSources sources = AccessSources.empty()
            .recording("10.13.13.6", "geir@example.com", NOON, geo)
            .recording("203.0.113.7", "geir@example.com", NOON, geo);

        assertThat(sources.sources()).hasSize(2);
        assertThat(sources.sources()).filteredOn(AccessSource::locatable).hasSize(1);
    }

    /**
     * The database places some addresses without naming them. Merging on the names alone put an unnamed
     * Oslo and an unnamed Singapore in one entry, drawn wherever the first of them was — one dot standing
     * for two continents.
     */
    @Test
    void twoNamelessLocationsFarApartStayTwoSources() {
        GeoLocation namelessOslo = new GeoLocation(59.91, 10.75, null, null);
        GeoLocation namelessSingapore = new GeoLocation(1.35, 103.82, null, null);
        ForGeolocatingIps geo =
            geoOf(Map.of("203.0.113.7", namelessOslo, "198.51.100.9", namelessSingapore));

        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, geo)
            .recording("198.51.100.9", "geir@example.com", NOON, geo);

        assertThat(sources.sources()).hasSize(2)
            .extracting(AccessSource::latitude).containsExactlyInAnyOrder(59.91, 1.35);
    }

    /** The same nameless place twice is still one place — the coordinates only split what genuinely differs. */
    @Test
    void theSameNamelessLocationTwiceIsStillOneSource() {
        GeoLocation namelessOslo = new GeoLocation(59.91, 10.75, null, null);
        ForGeolocatingIps geo = geoOf(Map.of("203.0.113.7", namelessOslo, "198.51.100.9", namelessOslo));

        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, geo)
            .recording("198.51.100.9", "kari@example.com", NOON.plusSeconds(5), geo);

        assertThat(sources.sources()).singleElement()
            .satisfies(source -> assertThat(source.count()).isEqualTo(2));
    }

    /** A geolocation lookup that blows up must not cost the count — it is simply not placeable. */
    @Test
    void aGeolocationLookupThatFailsFallsBackToTheUnplaceableSource() {
        ForGeolocatingIps broken = ip -> { throw new IllegalStateException("mmdb closed"); };

        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, broken);

        assertThat(sources.sources()).singleElement()
            .satisfies(source -> assertThat(source.locatable()).isFalse());
    }

    /** No port to ask is the same answer as a port that could not place the address: no place, still counted. */
    @Test
    void anAccessWithNoGeolocationPortAtAllIsStillCounted() {
        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, null);

        assertThat(sources.sources()).singleElement().satisfies(source -> {
            assertThat(source.isUnplaceable()).isTrue();
            assertThat(source.count()).isEqualTo(1);
            assertThat(source.people()).containsExactly("geir@example.com");
        });
    }

    // --- MAX_SOURCES ---

    /**
     * {@code MAX_PEOPLE} bounds one place; nothing bounded the number of places. Every recording copies the
     * whole list on the forward-auth path, so an inflated store is a cost every gated request pays.
     */
    @Test
    void capsTheNumberOfPlacesAndForgetsTheOneSeenLongestAgo() {
        AccessSources sources = AccessSources.empty();
        for (int i = 0; i <= AccessSources.MAX_SOURCES; i++) {
            GeoLocation city = new GeoLocation(1.0 + i, 2.0 + i, "City" + i, "Country");
            sources = sources.recording("203.0.113." + i, "geir@example.com", NOON.plusSeconds(i),
                geoOf(Map.of("203.0.113." + i, city)));
        }

        assertThat(sources.sources()).hasSize(AccessSources.MAX_SOURCES);
        assertThat(sources.sources()).extracting(AccessSource::city)
            .as("the place seen longest ago is the one evicted")
            .doesNotContain("City0")
            .contains("City1", "City" + AccessSources.MAX_SOURCES);
    }

    // --- pruned() ---

    @Test
    void prunedDropsSourcesWhoseLastAccessIsOlderThanAMonth() {
        ForGeolocatingIps geo = geoOf(Map.of("203.0.113.7", OSLO, "198.51.100.9", BERGEN));
        Instant longAgo = NOON.minus(AccessSource.RETENTION).minusSeconds(3600);

        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", longAgo, geo)
            .recording("198.51.100.9", "geir@example.com", NOON, geo);

        assertThat(sources.pruned(NOON).sources())
            .extracting(AccessSource::city).containsExactly("Bergen");
    }

    @Test
    void prunedKeepsASourceThatIsStillBeingUsed() {
        AccessSources sources = AccessSources.empty()
            .recording("203.0.113.7", "geir@example.com", NOON, geoOf(Map.of("203.0.113.7", OSLO)));

        assertThat(sources.pruned(NOON.plusSeconds(86400)).sources()).hasSize(1);
    }

    @Test
    void ofTolerantlyTreatsNoStoredSourcesAsEmpty() {
        assertThat(AccessSources.of(null).sources()).isEmpty();
        assertThat(AccessSources.of(List.of()).sources()).isEmpty();
    }
}
