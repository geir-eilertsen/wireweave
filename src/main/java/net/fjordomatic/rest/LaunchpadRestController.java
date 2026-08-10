package net.fjordomatic.rest;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import net.fjordomatic.application.GetLaunchpadServicesUseCase;
import net.fjordomatic.application.GetLaunchpadServicesUseCase.LaunchpadServiceUco;
import net.fjordomatic.application.ResolveViewerUseCase;
import net.fjordomatic.domain.AccessEntry;
import net.fjordomatic.domain.CallerIp;
import net.fjordomatic.domain.port.ForSubscribingToEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/launchpad")
@RequiredArgsConstructor
public class LaunchpadRestController {

    private final GetLaunchpadServicesUseCase getLaunchpadServicesUseCase;
    private final ResolveViewerUseCase resolveViewerUseCase;
    private final ForSubscribingToEvents forSubscribingToEvents;

    /** One boundary for both surfaces — see {@code AuthzRestController} for why the key is neutral. */
    @Value("${vaier.trusted-proxy-cidr:${launchpad.trusted-proxy-cidr:172.20.0.0/16}}")
    private String trustedProxyCidr;

    /**
     * Public launchpad listing. Returns only public (auth mode NONE) services — never social-gated
     * ones. This path stays anonymously reachable so a logged-out browser load of the launchpad can
     * fetch it without being redirected to sign in.
     */
    @GetMapping("/services")
    public List<LaunchpadServiceUco> getServices(HttpServletRequest request) {
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request), (AccessEntry) null);
    }

    /**
     * Public live-update stream for the launchpad. Emits the same publish/reachability event names as
     * the admin's {@code /published-services/events} but with an empty payload, so an anonymously-loaded
     * launchpad re-fetches its tiles when things change without ever being sent a (possibly private)
     * service subdomain. Stays anonymously reachable, like {@link #getServices}.
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return forSubscribingToEvents.subscribeSignalOnly("published-services");
    }

    /**
     * Viewer-adaptive launchpad listing. Served behind the identity-optional router (oauth2-authn
     * only): a valid session arrives with {@code X-Auth-Request-Email}, which resolves to the
     * viewer's {@link AccessEntry} so the listing includes exactly the social services that identity
     * may reach. An anonymous caller is stopped by oauth2-authn with a 401 before reaching here; the
     * launchpad page then falls back to {@link #getServices}. An authenticated-but-unknown/pending
     * identity resolves to no viewer and so sees public services only.
     */
    @GetMapping("/services-authenticated")
    public List<LaunchpadServiceUco> getServicesAuthenticated(
            HttpServletRequest request,
            @RequestHeader(value = "X-Auth-Request-Email", required = false) String email) {
        AccessEntry viewer = resolveViewerUseCase.resolveViewer(email).orElse(null);
        return getLaunchpadServicesUseCase.getLaunchpadServices(resolveCallerIp(request), viewer);
    }

    /**
     * Which hop to believe is {@link CallerIp}'s decision, not this controller's — the forward-auth check
     * asks the same question when it records where an allowed access came from, and a second copy of the
     * rule here is exactly the copy that would drift.
     */
    String resolveCallerIp(HttpServletRequest request) {
        return CallerIp.of(request.getRemoteAddr(), request.getHeader("X-Forwarded-For"), trustedProxyCidr)
            .value();
    }
}
