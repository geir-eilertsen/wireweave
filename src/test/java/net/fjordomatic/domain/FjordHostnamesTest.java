package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FjordHostnamesTest {

    @Test
    void fjordServerFqdn_prependsTheFjordSubdomain() {
        assertThat(new FjordHostnames("example.com").fjordServerFqdn())
            .isEqualTo("vaier.example.com");
    }

    @Test
    void oauth2Host_prependsTheOauth2Subdomain() {
        assertThat(new FjordHostnames("example.com").oauth2Host())
            .isEqualTo("oauth2.example.com");
    }

    @Test
    void dexHost_prependsTheDexSubdomain() {
        assertThat(new FjordHostnames("example.com").dexHost())
            .isEqualTo("dex.example.com");
    }

    @Test
    void oauth2SignOutUrl_clearsTheDomainWideCookieAndRedirectsBack() {
        assertThat(new FjordHostnames("example.com").oauth2SignOutUrl("https://vaier.example.com/"))
            .isEqualTo("https://oauth2.example.com/oauth2/sign_out?rd=https%3A%2F%2Fvaier.example.com%2F");
    }
}
