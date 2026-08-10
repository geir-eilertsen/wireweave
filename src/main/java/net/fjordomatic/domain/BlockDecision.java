package net.fjordomatic.domain;

import lombok.Builder;

import java.util.StringJoiner;

/**
 * One active CrowdSec ban. CrowdSec's own field for the banned address is named {@code value};
 * {@link #sourceIp} is the ubiquitous-language name for it. {@code duration} is kept as CrowdSec's own
 * string (e.g. {@code "3h59m48.13179286s"}) rather than reparsed into a {@link java.time.Duration}, since
 * the operator only ever needs to read it, never compute with it. {@code id} is the identity
 * {@link BreachAttemptTracker} diffs sweeps on.
 *
 * <p>{@code country}, {@code asnOrg}, {@code latitude} and {@code longitude} are CrowdSec's own geo/ASN
 * enrichment of the source and are all optional: a private-range address, or one CrowdSec could not place,
 * has none of them. Mapping the wire shape onto this record belongs to the driven adapter — the record
 * itself carries no serialisation coupling, so where the decisions are read from can change without
 * touching the domain.
 *
 * <p>Nine components, six of them strings and two of them {@code Double}, is exactly the shape that lets
 * two same-typed fields be swapped silently at a call site — hence {@link Builder}.
 */
@Builder
public record BlockDecision(Long id, String scenario, String sourceIp, String type, String duration,
                            String country, String asnOrg, Double latitude, Double longitude) {

    public BlockDecision {
        // An empty country is no country. CrowdSec sends "" rather than omitting the field for a source it
        // could not place, and an operator must never read an empty pair of brackets in a breach mail.
        country = presentOrNull(country);
        asnOrg = presentOrNull(asnOrg);
    }

    /**
     * What kind of threat this decision's scenario names, and therefore whether it is worth an email at
     * all. See {@link ThreatKind} for the rule and for why most decisions are deliberately silent.
     */
    public ThreatKind threatKind() {
        return ThreatKind.of(scenario);
    }

    /**
     * Whether this ban is keeping the <em>operator</em> out. True when the banned source falls inside the
     * fleet's {@link TrustedNetworks} — the VPN subnet, the Docker bridge, a relay's LAN, or an address the
     * operator trusted by hand.
     *
     * <p>Nobody is attacking when this is true. It means the allowlist that is supposed to make this
     * impossible has stopped working, and the operator is about to lose the console they would fix it from
     * — #329's first-named risk. It is the opposite of a breach attempt and must never be reported as one.
     *
     * @param trustedNetworks the operator's own networks, or null when they could not be assembled — in
     *                        which case nothing locks anybody out, because an alarm raised on missing
     *                        information is worse than no alarm. This is a guard, not the production
     *                        policy: the sweep defers itself entirely when it cannot read the allowlist,
     *                        since without it a lockout would be indistinguishable from a stranger's ban
     *                        and could be mailed as a breach attempt — the one thing it must never be
     *                        called. Nothing in production reaches here with null.
     */
    public boolean locksOut(TrustedNetworks trustedNetworks) {
        return trustedNetworks != null && trustedNetworks.contains(sourceIp);
    }

    /** Whether CrowdSec could say anything about where this source sits. Either half is enough. */
    public boolean enriched() {
        return country != null || asnOrg != null;
    }

    /**
     * Whether this attempt can honestly be drawn on the map. Both coordinates must be present, and the
     * pair must not be null island — CrowdSec writes {@code 0}/{@code 0} for a source it could not place,
     * and that point is a patch of Atlantic off Ghana, not an attacker. A marker there is a lie; no marker
     * is merely a gap. A genuine zero on one axis alone is a real place and stays on the map.
     *
     * <p>This is the domain's call, not the map's: a containment-style predicate that lives in JavaScript
     * is one every future caller has to rediscover.
     */
    public boolean locatable() {
        if (latitude == null || longitude == null) return false;
        return latitude != 0.0 || longitude != 0.0;
    }

    /**
     * How this reads to an operator: {@code "1.2.3.4 — crowdsecurity/http-probing (ban, 3h59m48s)"} bare,
     * and {@code "195.178.110.155 (BG · Techoff Srv Limited) — crowdsecurity/http-probing (ban, 3h0m40s)"}
     * when CrowdSec knew where the source sits.
     */
    public String label() {
        String origin = origin();
        return sourceIp + (origin.isEmpty() ? "" : " (" + origin + ")")
            + " — " + scenario + " (" + type + ", " + duration + ")";
    }

    /**
     * Where CrowdSec places this source, rendered for a person — {@code "BG · Techoff Srv Limited"}, or
     * either half alone, or empty when it could not place the source at all (i.e. exactly when
     * {@link #enriched()} is false).
     *
     * <p>Public, and deliberately so: the separator and the which-halves-are-known rule are one decision,
     * and every surface that shows an origin — the breach mail, the Security view, the Map popup — must
     * show the same one. A frontend that joins {@code country} and {@code asnOrg} itself has taken a copy
     * of this rule, and the copy is what drifts.
     */
    public String origin() {
        StringJoiner origin = new StringJoiner(" · ");
        if (country != null) origin.add(country);
        if (asnOrg != null) origin.add(asnOrg);
        return origin.toString();
    }

    private static String presentOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
