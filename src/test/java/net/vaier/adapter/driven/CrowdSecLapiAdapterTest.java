package net.vaier.adapter.driven;

import net.vaier.domain.BlockDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrowdSecLapiAdapterTest {

    private HttpClient httpClient;
    private HttpRequest sentRequest;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
    }

    private CrowdSecLapiAdapter adapter() {
        return new CrowdSecLapiAdapter(httpClient, "http://crowdsec:8080", "the-bouncer-key");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }

    private void respondWith(HttpResponse<String> response) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
            sentRequest = invocation.getArgument(0);
            return response;
        });
    }

    @Test
    void parsesTheRealDecisionsJsonShape() throws Exception {
        respondWith(response(200, """
            [
              {
                "duration": "3h59m48.13179286s",
                "id": 1,
                "origin": "crowdsec",
                "scenario": "crowdsecurity/http-probing",
                "scope": "Ip",
                "type": "ban",
                "value": "1.2.3.4"
              }
            ]
            """));

        List<BlockDecision> decisions = adapter().getActiveDecisions();

        assertThat(decisions).containsExactly(
            new BlockDecision(1L, "crowdsecurity/http-probing", "1.2.3.4", "ban", "3h59m48.13179286s"));
    }

    @Test
    void callsTheRealDecisionsEndpointWithTheApiKeyHeader() throws Exception {
        respondWith(response(200, "[]"));

        adapter().getActiveDecisions();

        assertThat(sentRequest.uri()).isEqualTo(URI.create("http://crowdsec:8080/v1/decisions"));
        assertThat(sentRequest.headers().firstValue("X-Api-Key")).contains("the-bouncer-key");
    }

    @Test
    void aNonTwoHundredResponseReadsAsNoActiveDecisions() throws Exception {
        respondWith(response(401, "unauthorized"));

        assertThat(adapter().getActiveDecisions()).isEmpty();
    }

    @Test
    void aNetworkFailureReadsAsNoActiveDecisionsNeverThrows() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new java.io.IOException("boom"));

        assertThat(adapter().getActiveDecisions()).isEmpty();
    }

    @Test
    void aBlankApiKeyReadsAsNoActiveDecisionsWithoutCallingLapiAtAll() {
        CrowdSecLapiAdapter adapter = new CrowdSecLapiAdapter(httpClient, "http://crowdsec:8080", " ");

        assertThat(adapter.getActiveDecisions()).isEmpty();
    }
}
