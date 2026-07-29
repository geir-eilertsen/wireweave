package net.vaier.domain;

import java.util.List;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPublicHost;

/**
 * The single {@code *.<domain>} A record that is the whole DNS story for a Vaier install without AWS
 * credentials. Every service Vaier publishes lands under it, so there is exactly one thing to get
 * right — and this is what checks that it was.
 *
 * <p>The probe labels are supplied by the caller (random slices, so no resolver's cache can answer for
 * a name that was never created) which keeps the check deterministic under test.
 *
 * <h2>Why the probe is two labels deep — do not "simplify" this to one</h2>
 * Vaier publishes machine-qualified hostnames two labels deep, e.g.
 * {@code openhab.colina27.eilertsen.family}. A DNS wildcard matches by <em>closest encloser</em>
 * (RFC 4592): {@code *.example.com} stops covering {@code anything.colina27.example.com} the moment the
 * zone gains any real record under {@code colina27}. A single-label probe
 * ({@code <random>.example.com}) is answered by the wildcard regardless, so it would report COVERED
 * while every machine-qualified service was dead — precisely the failure this check exists to catch.
 * Probing {@code <random>.<random>.example.com} exercises the depth Vaier actually publishes at.
 */
public record WildcardDns(String baseDomain) {

    /**
     * Looks up {@code probeLabel.probeParentLabel.baseDomain} against a public resolver and judges the
     * answer against this server's own public address.
     *
     * @param probeLabel       stands in for the service label, e.g. the {@code openhab} in
     *                         {@code openhab.colina27.example.com}
     * @param probeParentLabel stands in for the machine label, e.g. the {@code colina27}. This is the
     *                         label that makes the probe meaningful — see the class comment.
     */
    public WildcardDnsReport verify(String probeLabel, String probeParentLabel,
                                    ForResolvingDns resolver, ForResolvingPublicHost publicHost) {
        String probeFqdn = probeLabel + "." + probeParentLabel + "." + baseDomain;
        List<String> observed = resolver.resolveAddresses(probeFqdn);
        String expected = publicHost.resolvePublicIp().orElse(null);
        return new WildcardDnsReport(status(observed, expected), probeFqdn, expected, observed);
    }

    private WildcardDnsStatus status(List<String> observed, String expected) {
        if (observed.isEmpty()) return WildcardDnsStatus.NOT_RESOLVING;
        if (expected == null) return WildcardDnsStatus.UNCONFIRMED;
        return observed.contains(expected)
            ? WildcardDnsStatus.COVERED
            : WildcardDnsStatus.RESOLVES_ELSEWHERE;
    }
}
