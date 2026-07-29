package net.vaier.application.service;

import java.util.List;
import java.util.Optional;
import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.config.WildcardDnsStatusHolder;
import net.vaier.domain.WildcardDnsStatus;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LifecycleServiceTest {

    @Mock ForInitialisingVpnRouting forInitialisingVpnRouting;
    @Mock ForResolvingPublicHost publicHostResolver;
    @Mock ForResolvingDns dnsResolver;
    @Mock SetupStateHolder setupStateHolder;
    @Mock WildcardDnsStatusHolder wildcardDnsStatusHolder;
    @Mock ConfigResolver configResolver;
    @Mock SyncLanRoutesUseCase syncLanRoutesUseCase;
    @Mock ApplicationReadyEvent event;

    private LifecycleService service() {
        return new LifecycleService(
            forInitialisingVpnRouting,
            publicHostResolver,
            dnsResolver,
            setupStateHolder,
            wildcardDnsStatusHolder,
            configResolver,
            syncLanRoutesUseCase
        );
    }

    private void configured() {
        when(setupStateHolder.isConfigured()).thenReturn(true);
        when(configResolver.getDomain()).thenReturn("example.com");
    }

    @Test
    void skipsLifecycleWhenUnconfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(false);

        service().handle(event);

        verify(forInitialisingVpnRouting, never()).setupVpnRouting();
        verify(syncLanRoutesUseCase, never()).syncLanRoutes();
        verifyNoInteractions(dnsResolver, wildcardDnsStatusHolder);
    }

    @Test
    void runsSyncLanRoutesOnReadyWhenConfigured() {
        configured();
        when(dnsResolver.resolveAddresses(any())).thenReturn(List.of("52.29.74.114"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        service().handle(event);

        verify(syncLanRoutesUseCase).syncLanRoutes();
    }

    @Test
    void recordsTheWildcardVerdictSoTheSettingsPaneCanStateIt() {
        configured();
        when(dnsResolver.resolveAddresses(any())).thenReturn(List.of());
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        service().handle(event);

        verify(wildcardDnsStatusHolder)
            .record(argThat(r -> r.status() == WildcardDnsStatus.NOT_RESOLVING));
    }

    /**
     * The probe has to be two labels deep: that is the depth Vaier publishes at, and a wildcard matches
     * by closest encloser, so a one-label probe would report success over a zone where every
     * machine-qualified service was dead. Both labels are random, and independently so.
     */
    @Test
    void probesTwoIndependentRandomLabelsDeep() {
        configured();
        when(dnsResolver.resolveAddresses(any())).thenReturn(List.of("52.29.74.114"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        service().handle(event);

        ArgumentCaptor<String> probed = ArgumentCaptor.forClass(String.class);
        verify(dnsResolver).resolveAddresses(probed.capture());

        String[] labels = probed.getValue().split("\\.");
        assertThat(labels).hasSize(4);
        assertThat(probed.getValue()).endsWith(".example.com");
        assertThat(labels[0]).isNotEqualTo(labels[1]);
    }
}
