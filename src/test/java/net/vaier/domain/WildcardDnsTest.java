package net.vaier.domain;

import java.util.List;
import java.util.Optional;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WildcardDnsTest {

    private static final String PROBE = "9f3c1a";
    private static final String PROBE_PARENT = "b21d70";
    private static final String PROBE_FQDN = PROBE + "." + PROBE_PARENT + ".example.com";

    private static ForResolvingDns resolvesTo(List<String> addresses) {
        return fqdn -> {
            assertThat(fqdn).isEqualTo(PROBE_FQDN);
            return addresses;
        };
    }

    private static ForResolvingPublicHost publicIp(String ip) {
        return new ForResolvingPublicHost() {
            @Override
            public Optional<PublicHost> resolve() {
                return Optional.empty();
            }

            @Override
            public Optional<String> resolvePublicIp() {
                return Optional.ofNullable(ip);
            }
        };
    }

    private static WildcardDnsReport verify(ForResolvingDns resolver, ForResolvingPublicHost publicHost) {
        return new WildcardDns("example.com").verify(PROBE, PROBE_PARENT, resolver, publicHost);
    }

    /**
     * The reason the probe is two labels deep, asserted so nobody "simplifies" it back to one: Vaier
     * publishes machine-qualified names, and a wildcard matches by closest encloser (RFC 4592).
     */
    @Test
    void probesTwoLabelsDeep_becauseVaierPublishesMachineQualifiedNames() {
        WildcardDnsReport report = verify(resolvesTo(List.of("52.29.74.114")), publicIp("52.29.74.114"));

        assertThat(report.probeFqdn()).isEqualTo("9f3c1a.b21d70.example.com");
        assertThat(report.probeFqdn().split("\\.")).hasSize(4);
    }

    @Test
    void wildcardNameStripsBothProbeLabels_soTheOperatorIsToldAboutOneRecord() {
        WildcardDnsReport report = verify(resolvesTo(List.of()), publicIp("52.29.74.114"));

        assertThat(report.wildcardName()).isEqualTo("*.example.com");
    }

    @Test
    void covered_whenTheProbeResolvesToThisServer() {
        WildcardDnsReport report = verify(resolvesTo(List.of("52.29.74.114")), publicIp("52.29.74.114"));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.COVERED);
        assertThat(report.probeFqdn()).isEqualTo(PROBE_FQDN);
        assertThat(report.expectedAddress()).isEqualTo("52.29.74.114");
        assertThat(report.observedAddresses()).containsExactly("52.29.74.114");
        assertThat(report.message())
            .isEqualTo("Wildcard DNS is working — *.example.com resolves to 52.29.74.114.");
    }

    @Test
    void notResolving_whenTheProbeAnswersWithNothing() {
        WildcardDnsReport report = verify(resolvesTo(List.of()), publicIp("52.29.74.114"));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.NOT_RESOLVING);
        assertThat(report.message()).isEqualTo(
            "Wildcard DNS is not set up. Create one record — *.example.com A 52.29.74.114 — "
                + "and every service Vaier publishes will resolve.");
    }

    @Test
    void notResolving_namesTheAddressInWords_whenThisServersAddressIsUnknown() {
        WildcardDnsReport report = verify(resolvesTo(List.of()), publicIp(null));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.NOT_RESOLVING);
        assertThat(report.message()).isEqualTo(
            "Wildcard DNS is not set up. Create one record — *.example.com A this server's public IP "
                + "address — and every service Vaier publishes will resolve.");
    }

    @Test
    void resolvesElsewhere_namesWhatItPointsAtAndWhatItShouldPointAt() {
        WildcardDnsReport report = verify(resolvesTo(List.of("1.2.3.4")), publicIp("52.29.74.114"));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.RESOLVES_ELSEWHERE);
        assertThat(report.message()).isEqualTo(
            "Wildcard DNS points somewhere else — *.example.com resolves to 1.2.3.4, but this server "
                + "is 52.29.74.114. Point the record at 52.29.74.114 and every service Vaier publishes "
                + "will resolve.");
    }

    @Test
    void unconfirmed_whenTheProbeResolvesButThisServersAddressIsUnknown() {
        WildcardDnsReport report = verify(resolvesTo(List.of("1.2.3.4")), publicIp(null));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.UNCONFIRMED);
        assertThat(report.message()).isEqualTo(
            "*.example.com resolves to 1.2.3.4, but Vaier could not determine this server's own public "
                + "address, so it cannot confirm that is right.");
    }

    @Test
    void covered_whenTheProbeAnswersWithSeveralAddressesIncludingThisServer() {
        WildcardDnsReport report =
            verify(resolvesTo(List.of("1.2.3.4", "52.29.74.114")), publicIp("52.29.74.114"));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.COVERED);
    }

    /**
     * A single-label probe would be answered by the wildcard even on a zone where a machine label has
     * real records under it — reporting COVERED while every machine-qualified service was dead. Two
     * labels is what makes the check mean what it says.
     */
    @Test
    void reportsNotResolving_whenOnlyTheSingleLabelDepthIsCovered() {
        ForResolvingDns oneLabelDeepOnly = fqdn ->
            fqdn.split("\\.").length == 3 ? List.of("52.29.74.114") : List.of();

        WildcardDnsReport report = new WildcardDns("example.com")
            .verify(PROBE, PROBE_PARENT, oneLabelDeepOnly, publicIp("52.29.74.114"));

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.NOT_RESOLVING);
    }
}
