package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthModeTest {

    @Test
    void wireValue_isTheLowercaseToken() {
        assertThat(AuthMode.NONE.wireValue()).isEqualTo("none");
        assertThat(AuthMode.SOCIAL.wireValue()).isEqualTo("social");
    }

    @Test
    void fromString_parsesKnownTokensCaseInsensitively() {
        assertThat(AuthMode.fromString("none")).isEqualTo(AuthMode.NONE);
        assertThat(AuthMode.fromString("SOCIAL")).isEqualTo(AuthMode.SOCIAL);
    }

    @Test
    void fromString_unknownOrBlankDefaultsToSocial_soAuthIsNeverAccidentallyDropped() {
        assertThat(AuthMode.fromString(null)).isEqualTo(AuthMode.SOCIAL);
        assertThat(AuthMode.fromString("")).isEqualTo(AuthMode.SOCIAL);
        assertThat(AuthMode.fromString("nonsense")).isEqualTo(AuthMode.SOCIAL);
        // A legacy "authelia" token now falls through to the safe default rather than a dropped mode.
        assertThat(AuthMode.fromString("authelia")).isEqualTo(AuthMode.SOCIAL);
    }

    @Test
    void fromBoolean_mapsLegacyRequiresAuthToggle() {
        assertThat(AuthMode.fromRequiresAuth(true)).isEqualTo(AuthMode.SOCIAL);
        assertThat(AuthMode.fromRequiresAuth(false)).isEqualTo(AuthMode.NONE);
    }

    @Test
    void authMiddlewareNames_describeTheChainEachModeNeeds() {
        assertThat(AuthMode.NONE.authMiddlewareNames()).isEmpty();
        // The proven step-1 chain order: serve the sign-in page on 401, authenticate, then authorize.
        assertThat(AuthMode.SOCIAL.authMiddlewareNames())
            .containsExactly("oauth2-signin", "oauth2-authn", "vaier-authz");
    }

    @Test
    void allAuthMiddlewareNames_unionAcrossEveryMode_soAModeSwitchCanStripThePriorChain() {
        assertThat(AuthMode.allAuthMiddlewareNames())
            .containsExactlyInAnyOrder("oauth2-signin", "oauth2-authn", "vaier-authz");
    }

    @Test
    void fromMiddlewareNames_readsTheModeBackOffARoutersChain() {
        assertThat(AuthMode.fromMiddlewareNames(null)).isEqualTo(AuthMode.NONE);
        assertThat(AuthMode.fromMiddlewareNames(List.of("vaier-errors"))).isEqualTo(AuthMode.NONE);
        assertThat(AuthMode.fromMiddlewareNames(List.of("oauth2-signin", "oauth2-authn", "vaier-authz", "vaier-errors")))
            .isEqualTo(AuthMode.SOCIAL);
    }

    // --- isAuthMiddlewareName (#341) ---

    @Test
    void isAuthMiddlewareName_matchesTheAuthChainExactly() {
        assertThat(AuthMode.isAuthMiddlewareName("oauth2-signin")).isTrue();
        assertThat(AuthMode.isAuthMiddlewareName("oauth2-authn")).isTrue();
        assertThat(AuthMode.isAuthMiddlewareName("vaier-authz")).isTrue();
    }

    @Test
    void isAuthMiddlewareName_toleratesTraefiksProviderSuffix() {
        // The Traefik API returns middleware names qualified by their provider.
        assertThat(AuthMode.isAuthMiddlewareName("oauth2-authn@file")).isTrue();
        assertThat(AuthMode.isAuthMiddlewareName("vaier-authz@file")).isTrue();
    }

    @Test
    void isAuthMiddlewareName_rejectsANonAuthenticatingForwardAuth() {
        // forwardAuth is a general mechanism. A CrowdSec bouncer chained ahead of oauth2-proxy
        // rejects traffic; it does not authenticate anyone, so it must not gate a public service.
        assertThat(AuthMode.isAuthMiddlewareName("crowdsec-bouncer@file")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("crowdsec-forwardauth@file")).isFalse();
    }

    @Test
    void isAuthMiddlewareName_isPositiveIdentification_notAKeywordHeuristic() {
        // Every one of these matched the old "contains auth/oauth/sso" rule and none of them is
        // an authenticator Fjord emits. A blocklist would let the next such name back in by default.
        assertThat(AuthMode.isAuthMiddlewareName("authenticated-rate-limit")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("forward-auth")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("oauth-proxy")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("SSO")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("authelia@docker")).isFalse();
    }

    @Test
    void isAuthMiddlewareName_rejectsUnrelatedMiddlewareAndNull() {
        assertThat(AuthMode.isAuthMiddlewareName("strip-prefix")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("compress")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("vaier-frame-guard@file")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("vaier-errors")).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName(null)).isFalse();
        assertThat(AuthMode.isAuthMiddlewareName("  ")).isFalse();
    }

    @Test
    void isAuthMiddlewareName_agreesWithTheSingleSourcedChain() {
        // The rule is membership of allAuthMiddlewareNames() — stated once, so the list and the
        // membership test can never drift apart.
        assertThat(AuthMode.allAuthMiddlewareNames()).allMatch(AuthMode::isAuthMiddlewareName);
    }

    @Test
    void isSocial_isTrueOnlyForSocial() {
        assertThat(AuthMode.SOCIAL.isSocial()).isTrue();
        assertThat(AuthMode.NONE.isSocial()).isFalse();
    }
}
