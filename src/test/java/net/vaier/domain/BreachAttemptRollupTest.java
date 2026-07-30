package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BreachAttemptRollupTest {

    private static final BlockDecision DECISION_1 =
        new BlockDecision(1L, "crowdsecurity/http-probing", "1.2.3.4", "ban", "3h59m48s");
    private static final BlockDecision DECISION_2 =
        new BlockDecision(2L, "crowdsecurity/http-crawl-non_statics", "5.6.7.8", "ban", "4h0m0s");

    @Test
    void worthSending_isFalseWhenEmpty() {
        assertThat(new BreachAttemptRollup(List.of()).worthSending()).isFalse();
    }

    @Test
    void worthSending_isTrueWhenNotEmpty() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).worthSending()).isTrue();
    }

    @Test
    void subject_namesTheSingleDecision() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).subject())
            .isEqualTo("[Vaier] Breach attempt: " + DECISION_1.label());
    }

    @Test
    void subject_countsMultipleDecisions() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1, DECISION_2)).subject())
            .isEqualTo("[Vaier] Breach attempt: 2 new block decisions");
    }

    @Test
    void body_listsEveryDecision() {
        String body = new BreachAttemptRollup(List.of(DECISION_1, DECISION_2)).body(null);

        assertThat(body).contains(DECISION_1.label()).contains(DECISION_2.label());
    }

    @Test
    void body_omitsTheUiLinkWhenBaseDomainIsBlank() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).body(" ")).doesNotContain("Vaier UI");
    }

    @Test
    void body_includesTheUiLinkWhenBaseDomainIsGiven() {
        assertThat(new BreachAttemptRollup(List.of(DECISION_1)).body("example.com"))
            .contains("https://vaier.example.com/");
    }
}
