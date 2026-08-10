package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verdict's severity and its short label are decisions about the operator's situation, so they
 * belong here rather than being re-derived by whatever happens to render them (#331). The browser used
 * to own both, in a shape that already disagreed with the boot log.
 */
class WildcardDnsStatusTest {

    @Test
    void coveredIsTheOnlyStatusThatNeedsNothingFromTheOperator() {
        assertThat(WildcardDnsStatus.COVERED.getSeverity()).isEqualTo(WildcardDnsSeverity.OK);
    }

    @Test
    void anUnjudgeableAnswerIsACautionRatherThanAFailure() {
        // Names resolve; Fjord just cannot confirm they resolve *here*. That is worth saying, but it
        // is not evidence anything is broken.
        assertThat(WildcardDnsStatus.UNCONFIRMED.getSeverity()).isEqualTo(WildcardDnsSeverity.WARNING);
    }

    @Test
    void aMissingOrMisdirectedWildcardIsAnError() {
        assertThat(WildcardDnsStatus.NOT_RESOLVING.getSeverity()).isEqualTo(WildcardDnsSeverity.ERROR);
        assertThat(WildcardDnsStatus.RESOLVES_ELSEWHERE.getSeverity()).isEqualTo(WildcardDnsSeverity.ERROR);
    }

    @Test
    void labelsAreTheOperatorsWordsNotTheEnumsName() {
        assertThat(WildcardDnsStatus.COVERED.getLabel()).isEqualTo("Covered");
        assertThat(WildcardDnsStatus.NOT_RESOLVING.getLabel()).isEqualTo("Not resolving");
        assertThat(WildcardDnsStatus.RESOLVES_ELSEWHERE.getLabel()).isEqualTo("Resolves elsewhere");
        assertThat(WildcardDnsStatus.UNCONFIRMED.getLabel()).isEqualTo("Unconfirmed");
    }

    /**
     * A status added later must not render as {@code undefined} in the UI or drop out of the log's
     * severity choice — which is exactly what happened while these two lived in a JS lookup table.
     */
    @ParameterizedTest
    @EnumSource(WildcardDnsStatus.class)
    void everyStatusCarriesBothASeverityAndALabel(WildcardDnsStatus status) {
        assertThat(status.getSeverity()).isNotNull();
        assertThat(status.getLabel()).isNotBlank();
    }

    @Test
    void needsOperatorAction_isTrueForEverythingButCovered() {
        assertThat(WildcardDnsStatus.COVERED.needsOperatorAction()).isFalse();
        assertThat(WildcardDnsStatus.NOT_RESOLVING.needsOperatorAction()).isTrue();
        assertThat(WildcardDnsStatus.RESOLVES_ELSEWHERE.needsOperatorAction()).isTrue();
        assertThat(WildcardDnsStatus.UNCONFIRMED.needsOperatorAction()).isTrue();
    }
}
