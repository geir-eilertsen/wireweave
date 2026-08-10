package net.fjordomatic.domain;

import java.util.List;
import java.util.Optional;
import net.fjordomatic.domain.port.ForResolvingDns;
import net.fjordomatic.domain.port.ForResolvingPublicHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LifecycleTest {

    @Mock ForResolvingPublicHost publicHostResolver;
    @Mock ForResolvingDns dnsResolver;

    private static final String PROBE = "9f3c1a";
    private static final String PROBE_PARENT = "b21d70";
    private static final String PROBE_FQDN = PROBE + "." + PROBE_PARENT + ".test.example.com";

    private Lifecycle lifecycle() {
        return new Lifecycle(publicHostResolver, dnsResolver, "test.example.com");
    }

    @Test
    void verifiesTheWildcardWithARandomProbeTwoLabelsUnderTheDomain() {
        when(dnsResolver.resolveAddresses(PROBE_FQDN))
            .thenReturn(List.of("52.29.74.114"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        WildcardDnsReport report = lifecycle().start(PROBE, PROBE_PARENT);

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.COVERED);
        assertThat(report.probeFqdn()).isEqualTo(PROBE_FQDN);
    }

    @Test
    void reportsAMissingWildcardRatherThanFailingToStart() {
        when(dnsResolver.resolveAddresses(anyString())).thenReturn(List.of());
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        WildcardDnsReport report = lifecycle().start(PROBE, PROBE_PARENT);

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.NOT_RESOLVING);
        assertThat(report.message()).contains("*.test.example.com A 52.29.74.114");
    }

    @Test
    void reportsAWildcardPointingAtSomeOtherMachine() {
        when(dnsResolver.resolveAddresses(anyString())).thenReturn(List.of("198.51.100.7"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        WildcardDnsReport report = lifecycle().start(PROBE, PROBE_PARENT);

        assertThat(report.status()).isEqualTo(WildcardDnsStatus.RESOLVES_ELSEWHERE);
    }

    @Test
    void neverAsksHowThisServersPublicAddressIsExpressed() {
        when(dnsResolver.resolveAddresses(anyString())).thenReturn(List.of("52.29.74.114"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        lifecycle().start(PROBE, PROBE_PARENT);

        // The A-vs-CNAME question only mattered while Fjord wrote records. It writes none.
        verify(publicHostResolver, never()).resolve();
    }

    @Test
    void refusesToStartWithoutADomain() {
        Lifecycle noDomain = new Lifecycle(publicHostResolver, dnsResolver, "  ");

        assertThatThrownBy(() -> noDomain.start(PROBE, PROBE_PARENT))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("VAIER_DOMAIN");

        verifyNoInteractions(dnsResolver);
    }
}
