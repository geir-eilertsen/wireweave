package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveUserTest {

    @Test
    void of_rootLoginName_isPrivileged() {
        EffectiveUser user = EffectiveUser.of("root");

        assertThat(user.username()).isEqualTo("root");
        assertThat(user.privileged()).isTrue();
    }

    @Test
    void of_anyOtherLoginName_isNotPrivileged() {
        // A non-root user with passwordless sudo is still unprivileged for Fjord's own file operations —
        // the SFTP reads, writes and deletes the Explorer offers do not go through sudo. That is the
        // truthful answer to "what is the blast radius here", so it is the one Fjord gives.
        assertThat(EffectiveUser.of("ubuntu").privileged()).isFalse();
        assertThat(EffectiveUser.of("dietpi").privileged()).isFalse();
        assertThat(EffectiveUser.of("toor").privileged()).isFalse();
    }

    @Test
    void of_isNotFooledByCaseOrPadding() {
        // SSH login names are case-sensitive on Linux, so "Root" is a different account and Fjord must not
        // claim it is uid 0. Surrounding whitespace, on the other hand, is never part of a login name.
        assertThat(EffectiveUser.of("Root").privileged()).isFalse();
        assertThat(EffectiveUser.of("  root  ").privileged()).isTrue();
        assertThat(EffectiveUser.of("  root  ").username()).isEqualTo("root");
    }

    @Test
    void of_noUsername_isNobodyAndUnprivileged() {
        assertThat(EffectiveUser.of(null)).isNull();
        assertThat(EffectiveUser.of("   ")).isNull();
    }
}
