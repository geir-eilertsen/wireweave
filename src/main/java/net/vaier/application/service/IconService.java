package net.vaier.application.service;

import net.vaier.application.GetIconUseCase;
import net.vaier.domain.Icon;
import net.vaier.domain.IconResolution;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForFetchingIcons;
import net.vaier.domain.port.ForFetchingIcons.FetchedBytes;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForStoringIcons;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IconService implements GetIconUseCase {

    private final ForFetchingIcons forFetchingIcons;
    private final ForStoringIcons forStoringIcons;
    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    final Map<String, Optional<Icon>> cache = new ConcurrentHashMap<>();

    public IconService(ForFetchingIcons forFetchingIcons, ForStoringIcons forStoringIcons,
                       ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes) {
        this.forFetchingIcons = forFetchingIcons;
        this.forStoringIcons = forStoringIcons;
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
    }

    @Override
    public Optional<Icon> getIcon(String host, String pathPrefix) {
        String prefix = (pathPrefix == null) ? "" : pathPrefix;
        String cacheKey = IconResolution.cacheKey(host, prefix);

        Optional<Icon> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // Resolved icons survive restarts on disk — a disk hit skips both the fetch and resolution.
        Optional<Icon> onDisk = forStoringIcons.load(cacheKey);
        if (onDisk.isPresent()) {
            cache.put(cacheKey, onDisk);
            return onDisk;
        }

        String hostUrl = "https://" + host;
        String prefixedUrl = hostUrl + prefix;

        // The origin comes first: a social-gated service 401s every fetch of its public https://
        // address, because Vaier holds no oauth2 cookie — and only the origin has the app's own
        // <link rel="icon">. Never take the origin from the caller; that would be an open proxy.
        Optional<Icon> result = fromOrigin(host, prefix);
        if (result.isEmpty()) result = fromHtmlHint(prefixedUrl);
        if (result.isEmpty() && !prefix.isEmpty()) result = fetchIcon(prefixedUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/apple-touch-icon.png");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/apple-touch-icon-precomposed.png");
        if (result.isEmpty()) {
            for (String iconUrl : IconResolution.internetIconUrls(
                    IconResolution.cdnLookupName(host, prefix))) {
                result = fetchIcon(iconUrl);
                if (result.isPresent()) break;
            }
        }
        cache.put(cacheKey, result);
        // Persist positives only — an absent result is not written so a once-dead host can recover.
        result.ifPresent(icon -> forStoringIcons.store(cacheKey, icon));
        return result;
    }

    /** The icon as served by the backend itself, when a route tells us where that backend is. */
    private Optional<Icon> fromOrigin(String host, String prefix) {
        Optional<String> origin = ReverseProxyRoute.originUrlFor(
            forPersistingReverseProxyRoutes.getReverseProxyRoutes(), host, prefix);
        if (origin.isEmpty()) return Optional.empty();

        Optional<Icon> result = fromHtmlHint(origin.get() + prefix);
        // Root, not prefixed: the prefix is a Traefik matcher, and the backend usually serves at /.
        if (result.isEmpty()) result = fetchIcon(origin.get() + "/favicon.ico");
        return result;
    }

    private Optional<Icon> fromHtmlHint(String prefixedUrl) {
        Optional<String> html = forFetchingIcons.fetchHtml(prefixedUrl + "/");
        if (html.isEmpty()) return Optional.empty();
        return IconResolution.extractIconUrl(html.get(), prefixedUrl)
            .flatMap(this::fetchIcon);
    }

    private Optional<Icon> fetchIcon(String url) {
        return forFetchingIcons.fetchBytes(url)
            .filter(b -> b.body() != null && b.body().length > 0)
            .filter(b -> IconResolution.looksLikeImage(b.contentType(), b.body()))
            .map(IconService::toIcon);
    }

    private static Icon toIcon(FetchedBytes b) {
        return new Icon(b.body(), IconResolution.contentType(b.body()));
    }
}
