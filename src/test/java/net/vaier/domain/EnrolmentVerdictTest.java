package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three answers a held enrolment ticket can get, and nothing else: still waiting, here is your
 * config, or there is nothing here for you.
 */
class EnrolmentVerdictTest {

    @Test
    void gone_carriesNothingAtAll() {
        EnrolmentVerdict verdict = EnrolmentVerdict.gone();

        assertThat(verdict.isGone()).isTrue();
        assertThat(verdict.isPending()).isFalse();
        assertThat(verdict.isApproved()).isFalse();
        assertThat(verdict.configFile()).isNull();
    }

    @Test
    void pending_carriesNoConfigEither() {
        EnrolmentVerdict verdict = EnrolmentVerdict.pending();

        assertThat(verdict.isPending()).isTrue();
        assertThat(verdict.isApproved()).isFalse();
        assertThat(verdict.isGone()).isFalse();
        assertThat(verdict.configFile()).isNull();
    }

    @Test
    void approved_carriesTheConfigTheDeviceCameFor() {
        EnrolmentVerdict verdict = EnrolmentVerdict.approved("[Interface]\n");

        assertThat(verdict.isApproved()).isTrue();
        assertThat(verdict.configFile()).isEqualTo("[Interface]\n");
    }

    @Test
    void approved_withoutAConfigIsNotAnApproval() {
        // An "approved" verdict with nothing in it would close the phone's stream having delivered
        // nothing, and the request would be spent. Refuse to construct one.
        assertThatThrownBy(() -> EnrolmentVerdict.approved(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnrolmentVerdict.approved(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
