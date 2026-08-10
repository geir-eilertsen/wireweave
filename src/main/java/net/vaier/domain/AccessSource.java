package net.vaier.domain;

import lombok.Builder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * One place allowed accesses to Vaier-gated services have come from — the green dot on the map, and the
 * mirror image of a {@link BlockDecision}: who got in, rather than who was kept out. Everything Vaier gates
 * counts one, a published service and the console alike.
 *
 * <p><b>A place, not an address.</b> Accesses are aggregated per city, so one entry stands for every
 * request out of it however many addresses a household's ISP cycles through. That is also what keeps the
 * store bounded: the number of cities a person signs in from is small, the number of addresses is not.
 *
 * <p>{@code city}, {@code country}, {@code latitude} and {@code longitude} are all absent together on the
 * one unplaceable source — the bucket every access from a private, VPN or LAN address falls into, since
 * {@code ForGeolocatingIps} rightly places none of them. It is kept rather than dropped because discarding
 * those accesses would make the counts a lie.
 *
 * <p>Eight components, two adjacent {@code Double}s and two adjacent {@link Instant}s, is exactly the shape
 * that lets two same-typed fields be swapped silently at a call site — hence {@link Builder}.
 */
@Builder
public record AccessSource(String city, String country, Double latitude, Double longitude,
                           long count, Instant firstSeen, Instant lastSeen, List<String> people) {

    /**
     * How long a place is remembered after the most recent access from it. A month is long enough for the
     * map to say something about where this fleet is used from, and short enough that a place somebody
     * visited once, a year ago, stops being drawn as if it were still theirs.
     */
    public static final Duration RETENTION = Duration.ofDays(30);

    /**
     * The most people one place will ever name. Without a cap a shared office or campus address would
     * accumulate every identity that has ever signed in through it, and the file on disk would grow without
     * bound — the popup would be unreadable long before that mattered. The cap is a domain decision, not
     * the store's: a limit enforced by whoever happens to be writing the file is one every other writer can
     * skip.
     */
    public static final int MAX_PEOPLE = 20;

    /** What the display name of a place with no place is. */
    private static final String NOWHERE = "Not placeable";

    /**
     * The invariants, enforced here rather than only in the factories below. A record's canonical
     * constructor is as public as the record and cannot be narrowed, and {@link Builder} goes straight
     * through it — so a rule checked only in {@link #firstAccessFrom} would be documentation, not a
     * property of the code. The store reconstitutes sources through the builder, which is exactly the path
     * an older or hand-edited file arrives on.
     *
     * <p>Half a coordinate is the one worth spelling out: a latitude with no longitude either becomes null
     * island on the next round trip or draws a marker on the prime meridian. Both are lies, and the
     * carve-out in {@link #locatable()} exists precisely because a lie on a map is worse than a gap.
     */
    public AccessSource {
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("An access source has both coordinates or neither");
        }
        if (count < 1) {
            throw new IllegalArgumentException("An access source stands for at least one allowed access");
        }
        if (firstSeen == null || lastSeen == null) {
            throw new IllegalArgumentException("An access source knows when it was first and last seen");
        }
        people = people == null ? List.of() : List.copyOf(people);
    }

    /**
     * The first access from a place. {@code where} is null for an access that could not be placed, which
     * makes this the factory for the unplaceable source too — one door, so the "absent together" invariant
     * on the four location fields cannot be broken by a caller filling in half of them.
     */
    public static AccessSource firstAccessFrom(GeoLocation where, String person, Instant at) {
        return AccessSource.builder()
            .city(where == null ? null : where.city())
            .country(where == null ? null : where.country())
            .latitude(where == null ? null : where.latitude())
            .longitude(where == null ? null : where.longitude())
            .count(1)
            .firstSeen(at)
            .lastSeen(at)
            .people(person == null || person.isBlank() ? List.of() : List.of(person))
            .build();
    }

    /**
     * Whether this place can honestly be drawn on the map. Both coordinates must be present, and the pair
     * must not be null island — {@code 0}/{@code 0} is a patch of Atlantic off Ghana, not somewhere anybody
     * signed in from. A marker there is a lie; no marker is merely a gap. A genuine zero on one axis alone
     * is a real place (Quito sits on the equator) and stays on the map.
     *
     * <p>Deliberately mirrors {@link BlockDecision#locatable()}, and is shipped to the browser pre-decided
     * for the reason written there: {@code 0} is falsy in JavaScript, so a frontend {@code if (lat)} would
     * quietly delete the equator.
     */
    public boolean locatable() {
        if (latitude == null || longitude == null) return false;
        return latitude != 0.0 || longitude != 0.0;
    }

    /** Whether this is the one source standing for every access that had no place at all. */
    public boolean isUnplaceable() {
        return city == null && country == null && latitude == null && longitude == null;
    }

    /**
     * Whether an access from {@code where} belongs to this place — the merge rule, and the reason the map
     * shows one dot per city rather than one per address. A null {@code where} is an access that could not
     * be placed, and belongs only to the unplaceable source.
     *
     * <p>When neither side has a name the coordinates decide, because the names alone cannot: the database
     * places some addresses without naming them, and two such places are not one place just because both
     * are anonymous. Comparing names only merged an unnamed Oslo with an unnamed Singapore into a single
     * dot, drawn at whichever arrived first — a marker in the wrong place, which is the lie
     * {@link #locatable()}'s null-island carve-out exists to prevent.
     */
    public boolean isSamePlaceAs(GeoLocation where) {
        if (where == null) return isUnplaceable();
        if (isUnplaceable()) return false;
        if (!Objects.equals(city, where.city()) || !Objects.equals(country, where.country())) return false;
        if (city != null || country != null) return true;
        return Objects.equals(latitude, where.latitude()) && Objects.equals(longitude, where.longitude());
    }

    /**
     * This place having seen one more allowed access. The coordinates stay as first recorded: they are
     * city-level to begin with, and letting each access nudge them would move a dot around for no reason
     * anyone could read.
     *
     * <p>{@code lastSeen} never moves backwards. Requests arrive concurrently and clocks between hops are
     * not identical, so an out-of-order access must not make a live place look abandoned — and, through
     * {@link #isStale}, get itself pruned.
     */
    public AccessSource withAccess(String person, Instant at) {
        return AccessSource.builder()
            .city(city).country(country).latitude(latitude).longitude(longitude)
            .count(count + 1)
            .firstSeen(at.isBefore(firstSeen) ? at : firstSeen)
            .lastSeen(at.isAfter(lastSeen) ? at : lastSeen)
            .people(withPerson(person))
            .build();
    }

    /** Whether this place has gone quiet for longer than Vaier remembers a place for. */
    public boolean isStale(Instant now) {
        return lastSeen.isBefore(now.minus(RETENTION));
    }

    /**
     * How this place reads to a person: {@code "Oslo, Norway"}, either half alone when only one is known,
     * its coordinates when the database placed it without naming it, and {@code "Not placeable"} only for a
     * place there genuinely is none of. Public and single-homed for the same reason
     * {@link BlockDecision#origin()} is: every surface that names a place must name it the same way, and a
     * frontend that joins city and country itself has taken a copy of this rule.
     *
     * <p>The nowhere-label may never land on a {@link #locatable()} source: that source is about to get a
     * marker, and captioning it as being nowhere contradicts the dot it sits on.
     */
    public String place() {
        StringJoiner joined = new StringJoiner(", ");
        if (city != null) joined.add(city);
        if (country != null) joined.add(country);
        if (joined.length() > 0) return joined.toString();
        // Two decimals is city-level precision, which is all the coordinates ever were.
        return locatable()
            ? "Unnamed place at " + String.format(Locale.ROOT, "%.2f, %.2f", latitude, longitude)
            : NOWHERE;
    }

    /**
     * This place's people with {@code person} most recently seen. Re-adding an existing person moves them
     * to the end rather than duplicating them, so the cap always sheds whoever has been away longest.
     */
    private List<String> withPerson(String person) {
        if (person == null || person.isBlank()) return people;
        List<String> updated = new ArrayList<>(people);
        updated.remove(person);
        updated.add(person);
        return updated.size() > MAX_PEOPLE
            ? List.copyOf(updated.subList(updated.size() - MAX_PEOPLE, updated.size()))
            : updated;
    }
}
