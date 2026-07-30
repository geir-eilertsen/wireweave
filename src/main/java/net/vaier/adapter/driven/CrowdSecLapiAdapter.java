package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.port.ForDetectingIntrusions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Reads CrowdSec's active block decisions over LAPI's HTTP API (#329 Slice 2), using the same
 * bouncer API key ({@code VAIER_CROWDSEC_BOUNCER_KEY}) Slice 1 already minted for the
 * {@code crowdsec-bouncer} container — {@code GET /v1/decisions} authenticates with a bouncer key
 * fine, and carries everything a breach notification needs (scenario, source IP, duration). The
 * richer {@code /v1/alerts} endpoint demands a separate JWT-based "machine" credential for geo/ASN
 * enrichment nothing in this slice's acceptance criteria needs, so it is deliberately not chased.
 *
 * <p>Every failure is an empty list, never a throw — the same discipline as
 * {@code RegistryV2ImageAdapter}: an unreachable LAPI, a bad key, or a malformed response must
 * read as "nothing to report", not as every decision having just cleared.
 */
@Component
@Slf4j
public class CrowdSecLapiAdapter implements ForDetectingIntrusions {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public CrowdSecLapiAdapter() {
        this(HttpClient.newHttpClient(),
            System.getenv().getOrDefault("CROWDSEC_LAPI_URL", "http://crowdsec:8080"),
            System.getenv("VAIER_CROWDSEC_BOUNCER_KEY"));
    }

    CrowdSecLapiAdapter(HttpClient httpClient, String baseUrl, String apiKey) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public List<BlockDecision> getActiveDecisions() {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No CrowdSec bouncer key configured — reading no active decisions");
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/decisions"))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("X-Api-Key", apiKey)
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("CrowdSec LAPI answered {} for /v1/decisions", response.statusCode());
                return List.of();
            }
            return List.of(objectMapper.readValue(response.body(), BlockDecision[].class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.debug("Could not read CrowdSec's active decisions: {}", e.getMessage());
            return List.of();
        }
    }
}
