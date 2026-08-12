package net.vaier.domain;

import net.vaier.domain.port.ForGeolocatingIps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every place allowed accesses have come from, and the decisions that keep that collection honest: which
 * place an access belongs to, and when a place is old enough to forget.
 *
 * <p>Immutable — every recording returns a new collection. The service holds one reference and swaps it,
 * which is what lets the forward-auth hot path record an access without a lock around anything but the
 * swap, and without touching the disk at all.
 */
public record AccessSources(List<AccessSource> sources) {

    /**
     * The most places this collection will ever hold. {@link AccessSource#MAX_PEOPLE} bounds one place;
     * without this nothing bounded how many there are, and every recording copies the whole list on the
     * path that authenticates every request to every gated service — so an inflated store is a cost every
     * page load in the fleet pays. Two hundred is far more cities than a month of a real fleet's travel and
     * still a list the map can be read as; the least recently seen is what goes, because a place nobody has
     * come from lately is the one about to be pruned anyway.
     */
    public static final int MAX_SOURCES = 200;

    public AccessSources {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static AccessSources of(List<AccessSource> sources) {
        return new AccessSources(sources);
    }

    public static AccessSources empty() {
        return new AccessSources(List.of());
    }

    /**
     * This collection having seen one more allowed access from {@code callerIp}: merged into the place it
     * came from, or a new place if it is the first from there.
     *
     * <p>Takes the geolocation port rather than a resolved {@link GeoLocation} because placing the address
     * <em>is</em> part of the decision — the caller must not get to choose which place an access counts
     * towards. A lookup that fails, for any reason, is simply an access with no place: it still counts, in
     * the unplaceable source. Losing the count would be the worse answer, and this runs on the path that
     * authenticates every request to every gated service, where nothing may throw.
     *
     * <p>{@code server} is Vaier's own public address, resolved off this path and handed in — never looked
     * up here. A {@link ServerPublicAddress#isHairpin} caller is a full-tunnel peer's request turning
     * around through the server, so the address places the server and not the person: no place at all, and
     * the geolocation database is not asked. An unresolved address calls nothing a hairpin, which is what
     * keeps a lookup that has not happened yet from blanking the map.
     */
    public AccessSources recording(String callerIp, String person, Instant at, ForGeolocatingIps geo,
                                   ServerPublicAddress server) {
        GeoLocation where = server != null && server.isHairpin(callerIp) ? null : locate(callerIp, geo);
        List<AccessSource> updated = new ArrayList<>(sources.size() + 1);
        boolean merged = false;
        for (AccessSource source : sources) {
            if (!merged && source.isSamePlaceAs(where)) {
                updated.add(source.withAccess(person, at));
                merged = true;
            } else {
                updated.add(source);
            }
        }
        if (!merged) {
            updated.add(AccessSource.firstAccessFrom(where, person, at));
        }
        return new AccessSources(capped(updated));
    }

    /** This collection without the places that have gone quiet for longer than Vaier remembers them. */
    public AccessSources pruned(Instant now) {
        return new AccessSources(sources.stream().filter(source -> !source.isStale(now)).toList());
    }

    /**
     * At most {@link #MAX_SOURCES} places, keeping the most recently seen. Written to survive any overflow
     * rather than exactly one, because a store read back from a hand-edited file arrives here too.
     */
    private static List<AccessSource> capped(List<AccessSource> sources) {
        if (sources.size() <= MAX_SOURCES) return sources;
        return sources.stream()
            .sorted(Comparator.comparing(AccessSource::lastSeen).reversed())
            .limit(MAX_SOURCES)
            .toList();
    }

    private static GeoLocation locate(String callerIp, ForGeolocatingIps geo) {
        if (geo == null) return null;
        try {
            return geo.locate(callerIp).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
