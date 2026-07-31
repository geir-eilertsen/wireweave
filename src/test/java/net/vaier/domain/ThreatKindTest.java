package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static net.vaier.domain.ThreatKind.BLIND_SCANNING;
import static net.vaier.domain.ThreatKind.CREDENTIAL_ATTACK;
import static org.assertj.core.api.Assertions.assertThat;

class ThreatKindTest {

    /**
     * The whole live sample from the operator's own edge on day one: thirteen active decisions, every
     * one of them blind HTTP scanning. Nothing here is worth an email.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "crowdsecurity/http-probing",
        "crowdsecurity/http-wordpress-scan",
        "crowdsecurity/http-backdoors-attempts",
        "crowdsecurity/http-bad-user-agent",
        "crowdsecurity/http-crawl-non_statics",
        "crowdsecurity/http-path-traversal-probing",
        "crowdsecurity/http-sensitive-files",
        "crowdsecurity/http-open-proxy",
        "crowdsecurity/CVE-2017-9841",
        "crowdsecurity/http-cve-probing",
        "crowdsecurity/netscan",
        "crowdsecurity/http-w00tw00t",
        "crowdsecurity/http-admin-interface-probing"})
    void doorknobRattlingIsBlindScanning(String scenario) {
        assertThat(ThreatKind.of(scenario)).isEqualTo(BLIND_SCANNING);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "crowdsecurity/ssh-bf",
        "crowdsecurity/ssh-slow-bf",
        "crowdsecurity/http-bf",
        "crowdsecurity/http-generic-bf",
        "crowdsecurity/http-bf-wordpress_bf",
        "crowdsecurity/http-bf-wordpress_bf_xmlrpc",
        "crowdsecurity/mysql-bf",
        "LePresidente/grafana-bf",
        "firix/authentik-auth-bf",
        "crowdsecurity/ssh-bf_user-enum"})
    void someoneTryingCredentialsIsACredentialAttack(String scenario) {
        assertThat(ThreatKind.of(scenario)).isEqualTo(CREDENTIAL_ATTACK);
    }

    /**
     * The deliberate default. An unrecognised scenario is treated as blind scanning and stays silent,
     * because the cost of guessing wrong here is exactly the inbox noise this classification exists to
     * remove.
     */
    @Test
    void anUnrecognisedScenarioIsBlindScanning() {
        assertThat(ThreatKind.of("someone/a-scenario-vaier-has-never-heard-of")).isEqualTo(BLIND_SCANNING);
    }

    @Test
    void anAbsentScenarioIsBlindScanning() {
        assertThat(ThreatKind.of(null)).isEqualTo(BLIND_SCANNING);
        assertThat(ThreatKind.of(" ")).isEqualTo(BLIND_SCANNING);
    }

    /** The author namespace never decides the verdict — only the scenario's own name does. */
    @Test
    void theAuthorNamespaceIsIgnored() {
        assertThat(ThreatKind.of("bf/http-probing")).isEqualTo(BLIND_SCANNING);
        assertThat(ThreatKind.of("ssh-bf")).isEqualTo(CREDENTIAL_ATTACK);
    }

    @Test
    void onlyACredentialAttackIsWorthAnEmail() {
        assertThat(CREDENTIAL_ATTACK.worthEmailing()).isTrue();
        assertThat(BLIND_SCANNING.worthEmailing()).isFalse();
    }
}
