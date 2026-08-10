package net.fjordomatic.application;

import net.fjordomatic.domain.Icon;

import java.util.Optional;

public interface GetIconUseCase {

    /**
     * Resolve the icon for a published service identified by {@code host} (and optional
     * {@code pathPrefix}, used for path-routed services that share a hostname). Tries, in order:
     * the service's own backend origin (the only way in for a social-gated route, which answers
     * 401 to Fjord's cookie-less fetch of its public address), then that public address — its
     * {@code <link rel="icon">} hint and well-known fallback paths ({@code /favicon.ico},
     * {@code /apple-touch-icon.png}, …) — and finally external icon CDNs by service name.
     * Result includes the bytes and the deduced content-type the controller should report.
     */
    Optional<Icon> getIcon(String host, String pathPrefix);
}
