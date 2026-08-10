package net.fjordomatic.domain;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.port.ForResolvingDns;
import net.fjordomatic.domain.port.ForResolvingPublicHost;

/**
 * Startup bootstrap for Fjord's own DNS, which is now a single sentence: there is nothing to create.
 *
 * <p>One {@code *.<domain>} A record, made once by the operator at install, already answers for the
 * {@code vaier}, {@code oauth2} and {@code dex} infrastructure hosts and for every service Fjord will
 * ever publish (#331). So instead of writing DNS, Fjord <em>checks</em> it — once, at boot — and states
 * the result in words the operator can act on.
 */
@Slf4j
public class Lifecycle {

    private final ForResolvingPublicHost publicHostResolver;
    private final ForResolvingDns dnsResolver;
    private final String fjordDomain;

    public Lifecycle(
        ForResolvingPublicHost publicHostResolver,
        ForResolvingDns dnsResolver,
        String fjordDomain
    ) {
        this.publicHostResolver = publicHostResolver;
        this.dnsResolver = dnsResolver;
        this.fjordDomain = fjordDomain;
    }

    /**
     * Looks the one record up and says what it found. A wildcard that is missing or points elsewhere is
     * not a reason to refuse to start — Fjord comes up either way and tells the operator what to fix.
     *
     * @param wildcardProbeLabel       a random label to look up under the domain. The caller supplies it
     *                                 so no resolver can be holding a cached answer for a name that was
     *                                 never created — and so this check stays deterministic under test.
     * @param wildcardProbeParentLabel a second random label, one level up. Fjord publishes
     *                                 machine-qualified names two labels deep, and a wildcard matches by
     *                                 closest encloser — see {@link WildcardDns} for why probing at one
     *                                 label would report success over a broken zone.
     */
    public WildcardDnsReport start(String wildcardProbeLabel, String wildcardProbeParentLabel) {
        if (fjordDomain == null || fjordDomain.isBlank()) {
            throw new RuntimeException("VAIER_DOMAIN is not set");
        }
        WildcardDnsReport report = new WildcardDns(fjordDomain)
            .verify(wildcardProbeLabel, wildcardProbeParentLabel, dnsResolver, publicHostResolver);
        // The domain decides whether this is a problem — the same predicate the settings pane styles
        // its note by, so a log line and the UI can never disagree about a verdict.
        if (!report.status().needsOperatorAction()) {
            log.info(report.message());
        } else {
            log.warn("==========================================================");
            log.warn(report.message());
            log.warn("==========================================================");
        }
        return report;
    }
}
