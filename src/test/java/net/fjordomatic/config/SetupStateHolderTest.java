package net.fjordomatic.config;

import net.fjordomatic.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SetupStateHolderTest {

    @Mock ForPersistingAppConfiguration configPersistence;

    @Test
    void isConfigured_returnsTrueWhenConfigFileExists() {
        when(configPersistence.exists()).thenReturn(true);

        SetupStateHolder holder = new SetupStateHolder(configPersistence);

        assertThat(holder.isConfigured()).isTrue();
    }

    @Test
    void isConfigured_returnsFalseWhenNoConfigFileAndNoEnvVars() {
        when(configPersistence.exists()).thenReturn(false);

        SetupStateHolder holder = new SetupStateHolder(configPersistence);

        // Without env vars set in test environment, should be false
        assertThat(holder.isConfigured()).isFalse();
    }

    @Test
    void isConfiguredWhenDomainIsSet() {
        when(configPersistence.exists()).thenReturn(false);
        Map<String, String> env = Map.of("VAIER_DOMAIN", "example.com");

        SetupStateHolder holder = new SetupStateHolder(configPersistence, env::get);

        assertThat(holder.isConfigured()).isTrue();
    }

    /**
     * The domain is the whole question. Nothing else in the environment can stand in for it — and
     * nothing else is needed alongside it, now that DNS is one record the operator makes by hand (#331).
     */
    @Test
    void isNotConfiguredWhenDomainMissing_whateverElseIsSet() {
        when(configPersistence.exists()).thenReturn(false);
        Map<String, String> env = Map.of(
            "ACME_EMAIL", "admin@example.com",
            "VAIER_PUBLIC_IP", "52.29.74.114"
        );

        SetupStateHolder holder = new SetupStateHolder(configPersistence, env::get);

        assertThat(holder.isConfigured()).isFalse();
    }

    @Test
    void markConfigured_setsConfiguredTrue() {
        when(configPersistence.exists()).thenReturn(false);

        SetupStateHolder holder = new SetupStateHolder(configPersistence);
        assertThat(holder.isConfigured()).isFalse();

        holder.markConfigured();
        assertThat(holder.isConfigured()).isTrue();
    }
}
