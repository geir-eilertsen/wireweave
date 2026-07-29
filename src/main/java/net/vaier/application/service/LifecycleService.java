package net.vaier.application.service;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.config.WildcardDnsStatusHolder;
import net.vaier.domain.Lifecycle;
import net.vaier.domain.WildcardDnsReport;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LifecycleService {

    /**
     * How many hex characters of a fresh UUID make the probe label — unguessable enough that no
     * resolver can be holding a cached answer for it, short enough to read in a log line.
     */
    private static final int PROBE_LABEL_LENGTH = 12;

    private final ForInitialisingVpnRouting forInitialisingVpnRouting;
    private final ForResolvingPublicHost publicHostResolver;
    private final ForResolvingDns dnsResolver;
    private final SetupStateHolder setupStateHolder;
    private final WildcardDnsStatusHolder wildcardDnsStatusHolder;
    private final ConfigResolver configResolver;
    private final SyncLanRoutesUseCase syncLanRoutesUseCase;

    public LifecycleService(
        ForInitialisingVpnRouting forInitialisingVpnRouting,
        ForResolvingPublicHost publicHostResolver,
        ForResolvingDns dnsResolver,
        SetupStateHolder setupStateHolder,
        WildcardDnsStatusHolder wildcardDnsStatusHolder,
        ConfigResolver configResolver,
        SyncLanRoutesUseCase syncLanRoutesUseCase
    ) {
        this.forInitialisingVpnRouting = forInitialisingVpnRouting;
        this.publicHostResolver = publicHostResolver;
        this.dnsResolver = dnsResolver;
        this.setupStateHolder = setupStateHolder;
        this.wildcardDnsStatusHolder = wildcardDnsStatusHolder;
        this.configResolver = configResolver;
        this.syncLanRoutesUseCase = syncLanRoutesUseCase;
    }

    @EventListener
    public void handle(ApplicationReadyEvent event) {
        if (!setupStateHolder.isConfigured()) {
            log.info("Vaier is not configured. Set VAIER_DOMAIN in .env, create one DNS record "
                + "(*.<your domain> A <this server's public IP>), and restart the stack.");
            return;
        }

        log.info("Application is ready, starting lifecycle...");
        runLifecycle();
    }

    public void runLifecycle() {
        configResolver.reload();
        WildcardDnsReport report = new Lifecycle(
            publicHostResolver,
            dnsResolver,
            configResolver.getDomain()
        ).start(probeLabel(), probeLabel());
        wildcardDnsStatusHolder.record(report);

        forInitialisingVpnRouting.setupVpnRouting();
        syncLanRoutesUseCase.syncLanRoutes();
    }

    /**
     * A random label to look up under the domain. Generated here, not in the domain, so the check
     * itself stays deterministic and testable. Called twice per run — the probe is two labels deep and
     * the two slices must be independent, so that neither label can be a name the zone actually has.
     */
    private String probeLabel() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, PROBE_LABEL_LENGTH);
    }
}
