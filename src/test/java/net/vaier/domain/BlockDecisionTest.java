package net.vaier.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockDecisionTest {

    @Test
    void labelReadsTheAddressScenarioAndDuration() {
        BlockDecision decision = new BlockDecision(1L, "crowdsecurity/http-probing", "1.2.3.4", "ban",
            "3h59m48.13179286s");

        assertThat(decision.label())
            .isEqualTo("1.2.3.4 — crowdsecurity/http-probing (ban, 3h59m48.13179286s)");
    }

    // CrowdSec's LAPI names the banned address "value", not "sourceIp" — this proves the domain record
    // deserialises the real /v1/decisions shape directly, with no intermediate DTO.
    @Test
    void deserialisesCrowdSecsActualDecisionJsonShape() throws Exception {
        String json = """
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
            """;

        List<BlockDecision> decisions = List.of(new ObjectMapper().readValue(json, BlockDecision[].class));

        assertThat(decisions).containsExactly(
            new BlockDecision(1L, "crowdsecurity/http-probing", "1.2.3.4", "ban", "3h59m48.13179286s"));
    }
}
