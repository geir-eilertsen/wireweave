package net.fjordomatic.integration.adapter;

import net.fjordomatic.adapter.driven.FjordConfigFileAdapter;
import net.fjordomatic.domain.FjordConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for FjordConfigFileAdapter against a real temp directory.
 */
class FjordConfigFileAdapterIT {

    @TempDir
    java.nio.file.Path tempDir;

    FjordConfigFileAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FjordConfigFileAdapter(tempDir.toString());
    }

    @Test
    void load_returnsEmptyWhenFileDoesNotExist() {
        assertThat(adapter.load()).isEmpty();
    }

    @Test
    void exists_returnsFalseBeforeSave() {
        assertThat(adapter.exists()).isFalse();
    }

    @Test
    void exists_returnsTrueAfterSave() {
        adapter.save(FjordConfig.builder().domain("example.com").build());
        assertThat(adapter.exists()).isTrue();
    }

    @Test
    void saveAndLoad_roundTripsAllFields() {
        FjordConfig config = FjordConfig.builder()
                .domain("example.com")
                .acmeEmail("admin@example.com")
                .smtpHost("smtp.example.com")
                .smtpPort(587)
                .smtpUsername("user@example.com")
                .smtpSender("noreply@example.com")
                .build();

        adapter.save(config);
        Optional<FjordConfig> loaded = adapter.load();

        assertThat(loaded).isPresent();
        FjordConfig result = loaded.get();
        assertThat(result.getDomain()).isEqualTo("example.com");
        assertThat(result.getAcmeEmail()).isEqualTo("admin@example.com");
        assertThat(result.getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(result.getSmtpPort()).isEqualTo(587);
        assertThat(result.getSmtpUsername()).isEqualTo("user@example.com");
        assertThat(result.getSmtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void secondSave_overwritesFirstSave() {
        adapter.save(FjordConfig.builder().domain("first.com").build());
        adapter.save(FjordConfig.builder().domain("second.com").build());

        Optional<FjordConfig> loaded = adapter.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDomain()).isEqualTo("second.com");
    }

    @Test
    void saveWithNullFields_loadReturnsNulls() {
        adapter.save(FjordConfig.builder().domain("example.com").build());

        Optional<FjordConfig> loaded = adapter.load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpHost()).isNull();
        assertThat(loaded.get().getSmtpPort()).isNull();
    }

    @Test
    void autoCreatesParentDirectories() {
        FjordConfigFileAdapter nestedAdapter = new FjordConfigFileAdapter(
                tempDir.resolve("vaier").resolve("config").toString());

        nestedAdapter.save(FjordConfig.builder().domain("example.com").build());

        assertThat(nestedAdapter.load()).isPresent();
    }
}
