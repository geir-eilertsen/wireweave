package net.vaier.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import net.vaier.config.ServiceNames;

/**
 * The Vaier server's own public hostnames, derived from the base domain. Replaces the
 * {@code "vaier." + domain} string concatenation that was duplicated across the application and
 * adapter layers — the subdomain labels are the single {@link ServiceNames} definitions.
 *
 * <p>All three are covered by the operator's single {@code *.<baseDomain>} A record, so Vaier only
 * ever names them — it never creates them (#331).
 */
public record VaierHostnames(String baseDomain) {

    /** The FQDN the Vaier web UI is served on, e.g. {@code vaier.example.com}. */
    public String vaierServerFqdn() {
        return ServiceNames.VAIER + "." + baseDomain;
    }

    /** The FQDN oauth2-proxy is served on for social login, e.g. {@code oauth2.example.com}. */
    public String oauth2Host() {
        return ServiceNames.OAUTH2 + "." + baseDomain;
    }

    /** The FQDN the Dex OIDC broker is served on, e.g. {@code dex.example.com}. */
    public String dexHost() {
        return ServiceNames.DEX + "." + baseDomain;
    }

    /**
     * The URL that logs a social-login session out: oauth2-proxy's {@code /oauth2/sign_out}, which
     * clears the domain-wide SSO cookie, then redirects back to {@code redirectTarget}. The
     * redirect target must fall under {@code .baseDomain} (oauth2-proxy's whitelist-domain).
     */
    public String oauth2SignOutUrl(String redirectTarget) {
        return "https://" + oauth2Host() + "/oauth2/sign_out?rd="
            + URLEncoder.encode(redirectTarget, StandardCharsets.UTF_8);
    }
}
