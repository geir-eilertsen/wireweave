package net.vaier.domain;

import net.vaier.config.ServiceNames;

import java.util.List;
import java.util.Locale;

/**
 * How a published route is gated. There are two modes:
 *
 * <ul>
 *   <li>{@link #NONE} — public, no auth middleware.</li>
 *   <li>{@link #SOCIAL} — the proven two-stage chain: serve the oauth2-proxy sign-in page on
 *       401 ({@code oauth2-signin}), authenticate with Google ({@code oauth2-authn}), then authorize
 *       against Vaier's access store ({@code vaier-authz}).</li>
 * </ul>
 *
 * The decision of <em>which middleware chain a route needs</em> lives here, on the mode — the
 * Traefik adapter only translates the names into YAML.
 */
public enum AuthMode {
    NONE,
    SOCIAL;

    /** The lowercase token surfaced over the wire and used in patches. */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parse a wire token. Unknown, blank or null values read as {@link #SOCIAL} — the safe default,
     * so a malformed value never silently drops authentication from a protected service.
     */
    public static AuthMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return SOCIAL;
        }
        try {
            return AuthMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SOCIAL;
        }
    }

    /** Map the legacy {@code requiresAuth} toggle: {@code true} → social login, {@code false} → none. */
    public static AuthMode fromRequiresAuth(boolean requiresAuth) {
        return requiresAuth ? SOCIAL : NONE;
    }

    /** Whether this is the social-login mode (needs oauth2-proxy + the per-host {@code /oauth2/} router). */
    public boolean isSocial() {
        return this == SOCIAL;
    }

    /**
     * The ordered auth-middleware names this mode prepends to a router's middleware chain. The
     * redirect and offline-page middlewares are appended by the adapter after these.
     */
    public List<String> authMiddlewareNames() {
        return switch (this) {
            case NONE -> List.of();
            case SOCIAL -> List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE,
                                   ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                                   ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
        };
    }

    /**
     * Every auth-middleware name any mode can emit. A mode switch strips all of these from the
     * router before prepending the new mode's chain, so no stale links from the prior mode remain.
     */
    public static List<String> allAuthMiddlewareNames() {
        return List.of(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE,
                       ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
                       ServiceNames.VAIER_AUTHZ_MIDDLEWARE);
    }

    /**
     * Whether {@code middlewareName} names one of Vaier's authentication middlewares — exact
     * membership of {@link #allAuthMiddlewareNames()}, so the list of authenticators and the test for
     * "is this an authenticator?" are the same statement and cannot drift.
     *
     * <p>Identification is <em>positive</em> on purpose. It used to be a keyword heuristic
     * ({@code contains("auth")}), and a Traefik {@code forwardAuth} block used to be taken as proof on
     * its own — but {@code forwardAuth} is a transport, not a verdict. Plenty of middlewares that
     * authenticate nobody use it (a CrowdSec bouncer, a rate limiter, a geo-filter, a maintenance
     * gate), and any of them chained ahead of oauth2-proxy made a deliberately public service report
     * as gated. A blocklist would let the next such name back in by default; membership of the chain
     * Vaier itself emits cannot.
     *
     * <p>Traefik qualifies names by provider when read back over its API ({@code oauth2-authn@file}),
     * so the suffix is stripped before comparing.
     */
    public static boolean isAuthMiddlewareName(String middlewareName) {
        if (middlewareName == null || middlewareName.isBlank()) {
            return false;
        }
        String bare = middlewareName.trim();
        int at = bare.indexOf('@');
        if (at >= 0) {
            bare = bare.substring(0, at);
        }
        return allAuthMiddlewareNames().contains(bare);
    }

    /**
     * Read the mode back off a router's middleware list. Social wins when its forward-auth links are
     * present, else none — so the published-services API and the UI picker reflect what the route
     * actually carries.
     */
    public static AuthMode fromMiddlewareNames(List<String> middlewares) {
        if (middlewares == null) {
            return NONE;
        }
        if (middlewares.contains(ServiceNames.OAUTH2_AUTHN_MIDDLEWARE)
                || middlewares.contains(ServiceNames.VAIER_AUTHZ_MIDDLEWARE)) {
            return SOCIAL;
        }
        return NONE;
    }
}
