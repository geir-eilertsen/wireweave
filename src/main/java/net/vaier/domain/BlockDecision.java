package net.vaier.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One active CrowdSec ban, read straight off {@code GET /v1/decisions} — no intermediate DTO. CrowdSec's own
 * field is named {@code value}, not {@code sourceIp}; {@link #sourceIp} is the ubiquitous-language name for
 * it. {@code duration} is kept as CrowdSec's own string (e.g. {@code "3h59m48.13179286s"}) rather than
 * reparsed into a {@link java.time.Duration}, since the operator only ever needs to read it, never compute
 * with it. {@code id} is the identity {@link BreachAttemptTracker} diffs sweeps on.
 *
 * <p>{@code ignoreUnknown} because the real response carries fields this slice's acceptance criteria has no
 * use for ({@code origin}, {@code scope}, {@code uuid}, {@code until}, ...) — see the plan's note on why
 * {@code /v1/alerts}' richer enrichment was deliberately not chased.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BlockDecision(Long id, String scenario, @JsonProperty("value") String sourceIp, String type,
                             String duration) {

    /** How this reads to an operator, e.g. {@code "1.2.3.4 — crowdsecurity/http-probing (ban, 3h59m48s)"}. */
    public String label() {
        return sourceIp + " — " + scenario + " (" + type + ", " + duration + ")";
    }
}
