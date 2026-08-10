package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.FjordConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FjordConfigFileAdapterTest {

    @TempDir
    Path tempDir;

    private FjordConfigFileAdapter adapter() {
        return new FjordConfigFileAdapter(tempDir.toString());
    }

    @Test
    void exists_returnsFalseWhenNoConfigFile() {
        assertThat(adapter().exists()).isFalse();
    }

    @Test
    void exists_returnsTrueAfterSave() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .build();

        adapter().save(config);

        assertThat(adapter().exists()).isTrue();
    }

    @Test
    void save_writesYamlFile() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .build();

        adapter().save(config);

        Path configFile = tempDir.resolve("vaier-config.yml");
        assertThat(configFile).exists();
    }

    @Test
    void load_returnsEmptyWhenNoConfigFile() {
        assertThat(adapter().load()).isEmpty();
    }

    @Test
    void load_roundTripsConfig() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDomain()).isEqualTo("example.com");
        assertThat(loaded.get().getAcmeEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void save_createsParentDirectoriesIfNeeded() {
        Path nested = tempDir.resolve("nested/deep/config");
        FjordConfigFileAdapter nestedAdapter = new FjordConfigFileAdapter(nested.toString());

        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("a@b.com")
            .build();

        nestedAdapter.save(config);

        assertThat(nested.resolve("vaier-config.yml")).exists();
    }

    @Test
    void load_returnsEmptyForCorruptFile() throws IOException {
        Files.writeString(tempDir.resolve("vaier-config.yml"), "not: valid: yaml: {{{}}}");

        // Should not throw, just return empty
        Optional<FjordConfig> loaded = adapter().load();
        // Corrupt YAML may parse partially — the key thing is no exception
        assertThat(loaded).isNotNull();
    }

    @Test
    void load_roundTripsSmtpFields() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .smtpHost("smtp.example.com")
            .smtpPort(587)
            .smtpUsername("user@example.com")
            .smtpSender("noreply@example.com")
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(loaded.get().getSmtpPort()).isEqualTo(587);
        assertThat(loaded.get().getSmtpUsername()).isEqualTo("user@example.com");
        assertThat(loaded.get().getSmtpSender()).isEqualTo("noreply@example.com");
    }

    @Test
    void load_roundTripsDiskMonitorThreshold() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .diskMonitorThresholdPercent(70)
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDiskMonitorThresholdPercent()).isEqualTo(70);
    }

    @Test
    void load_roundTripsBackupScheduleHour() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .backupScheduleHour(5)
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getBackupScheduleHour()).isEqualTo(5);
    }

    @Test
    void load_backupScheduleHourNullWhenNotPresent() {
        adapter().save(FjordConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().getBackupScheduleHour()).isNull();
    }

    @Test
    void load_diskMonitorThresholdNullWhenNotPresent() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDiskMonitorThresholdPercent()).isNull();
    }

    @Test
    void save_writesConfigFileWithOwnerOnlyPermissions() throws IOException {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .build();

        adapter().save(config);

        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tempDir.resolve("vaier-config.yml"));
        assertThat(perms).containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void readStoredPassword_roundTripsTheSmtpPassword() {
        // The config file is Fjord's own SMTP-credential store now that Authelia's secrets file is gone.
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .smtpHost("smtp.example.com")
            .smtpUsername("user@example.com")
            .smtpPassword("s3cr3t")
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        assertThat(adapter().readStoredPassword()).contains("s3cr3t");
    }

    @Test
    void readStoredPassword_isEmptyWhenNoPasswordStored() {
        FjordConfig config = FjordConfig.builder().domain("example.com").build();
        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        assertThat(adapter().readStoredPassword()).isEmpty();
    }

    /**
     * The kit passphrase is the third reversible secret in this file, and it is encrypted like the other two.
     * On this host that is nearly theatre — {@code vault.key} sits in the same directory — but the config
     * file travels (into a backup archive, into a support paste) where the key does not.
     */
    @Test
    void save_thenLoad_roundTripsTheSurvivalKitPassphraseEncrypted() throws IOException {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .survivalKitPassphrase("the-kit-passphrase")
            .build();

        adapter().save(config);

        assertThat(Files.readString(tempDir.resolve("vaier-config.yml")))
            .doesNotContain("the-kit-passphrase")
            .contains("enc:v1:");
        assertThat(adapter().load().orElseThrow().getSurvivalKitPassphrase())
            .isEqualTo("the-kit-passphrase");
    }

    /**
     * The fingerprint is stored in the clear, unlike the passphrase beside it. It is a digest of a page whose
     * every line already lives in this file, and it has to be readable to be compared — encrypting it would
     * buy nothing and cost the comparison.
     */
    @Test
    void save_thenLoad_roundTripsTheSurvivalKitFingerprintInTheClear() throws IOException {
        adapter().save(FjordConfig.builder()
            .domain("example.com")
            .survivalKitFingerprint("d41d8cd98f00b204")
            .build());

        assertThat(Files.readString(tempDir.resolve("vaier-config.yml"))).contains("d41d8cd98f00b204");
        assertThat(adapter().load().orElseThrow().getSurvivalKitFingerprint())
            .isEqualTo("d41d8cd98f00b204");
    }

    @Test
    void load_survivalKitPassphraseIsNullWhenNoneHasBeenChosen() {
        adapter().save(FjordConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().hasSurvivalKitPassphrase()).isFalse();
    }

    // --- at-rest secret encryption (#307) ---

    @Test
    void save_encryptsSmtpPasswordAtRest() throws IOException {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .smtpHost("smtp.example.com")
            .smtpUsername("user@example.com")
            .smtpPassword("the-smtp-password")
            .build();

        adapter().save(config);

        String contents = Files.readString(tempDir.resolve("vaier-config.yml"));
        assertThat(contents)
            .doesNotContain("the-smtp-password")
            .contains("enc:v1:")
            // non-secret fields stay in the clear.
            .contains("example.com");
    }

    @Test
    void save_thenLoad_roundTripsEncryptedSecrets() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .smtpPassword("the-smtp-password")
            .build();

        adapter().save(config);

        Optional<FjordConfig> loaded = adapter().load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpPassword()).isEqualTo("the-smtp-password");
    }

    @Test
    void load_legacyPlaintextSecrets_loadUnchanged() throws IOException {
        // A pre-#307 config file has smtpPassword in the clear — it must still load.
        Files.writeString(tempDir.resolve("vaier-config.yml"), """
            domain: example.com
            acmeEmail: admin@example.com
            smtpPassword: legacy-plain-smtp
            """);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpPassword()).isEqualTo("legacy-plain-smtp");
    }

    @Test
    void legacyPlaintext_isEncryptedOnNextSave() throws IOException {
        Files.writeString(tempDir.resolve("vaier-config.yml"), """
            domain: example.com
            acmeEmail: admin@example.com
            smtpPassword: legacy-plain-smtp
            """);

        FjordConfigFileAdapter adapterInstance = adapter();
        FjordConfig loaded = adapterInstance.load().orElseThrow();
        adapterInstance.save(loaded);

        String contents = Files.readString(tempDir.resolve("vaier-config.yml"));
        assertThat(contents).doesNotContain("legacy-plain-smtp").contains("enc:v1:");
        assertThat(adapter().load().orElseThrow().getSmtpPassword()).isEqualTo("legacy-plain-smtp");
    }

    // --- stale key tolerance (#331 — DNS/AWS keys removed from FjordConfig) ---

    /**
     * The live install's on-disk vaier-config.yml still has awsKey/awsSecret from before #331 removed
     * Route53 support entirely. FjordConfigFileAdapter.load() only ever names the keys it wants out of
     * the parsed Map — it never binds the whole document — so a stale key (or any other unrecognised
     * key) must be silently ignored rather than blowing up the boot. A boot that fails on a stale key
     * takes the live install down.
     */
    @Test
    void load_toleratesStaleAwsKeysAndUnknownKeysAlongsideRealFields() throws IOException {
        Files.writeString(tempDir.resolve("vaier-config.yml"), """
            domain: example.com
            awsKey: AKIASTALEKEY12345
            awsSecret: some-stale-aws-secret
            acmeEmail: admin@example.com
            someFutureUnknownKey: whatever-a-newer-vaier-version-once-wrote
            smtpHost: smtp.example.com
            """);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDomain()).isEqualTo("example.com");
        assertThat(loaded.get().getAcmeEmail()).isEqualTo("admin@example.com");
        assertThat(loaded.get().getSmtpHost()).isEqualTo("smtp.example.com");
    }

    // --- Fjord-server machine identity ---

    /**
     * The keys on disk are named vaierServer*, and they stay that way whatever the Java fields are
     * called. Every existing install has them written already, and load() names its keys as string
     * literals — so a rename of the field silently reads null instead of failing, and the server
     * quietly mints itself a *new* identity, orphaning everything keyed to the old one. The
     * round-trip tests below cannot catch that: they save and load through the same literal, so they
     * pass under any name. This one reads a file the running install could have written.
     */
    @Test
    void load_readsTheOnDiskVaierServerKeys() throws IOException {
        Files.writeString(tempDir.resolve("vaier-config.yml"), """
            domain: example.com
            vaierServerMachineId: c0355605-e5a0-419a-8943-fdc5ec209958
            vaierServerSshAccess: false
            """);

        FjordConfig loaded = adapter().load().orElseThrow();

        assertThat(loaded.getFjordServerMachineId()).isEqualTo("c0355605-e5a0-419a-8943-fdc5ec209958");
        assertThat(loaded.getFjordServerSshAccess()).isFalse();
    }

    @Test
    void load_roundTripsFjordServerMachineId() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .fjordServerMachineId("3f2504e0-4f89-41d3-9a0c-0305e82c3301")
            .build();

        adapter().save(config);

        assertThat(adapter().load().orElseThrow().getFjordServerMachineId())
            .isEqualTo("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    }

    @Test
    void load_fjordServerMachineIdNullWhenNotPresent() {
        adapter().save(FjordConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().getFjordServerMachineId()).isNull();
    }

    // --- Fjord-server SSH access (#311) ---

    @Test
    void load_roundTripsFjordServerSshAccess() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .fjordServerSshAccess(false)
            .build();

        adapter().save(config);

        Optional<FjordConfig> loaded = adapter().load();
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getFjordServerSshAccess()).isFalse();
    }

    @Test
    void load_fjordServerSshAccessNullWhenNotPresent() {
        adapter().save(FjordConfig.builder().domain("example.com").build());

        assertThat(adapter().load().orElseThrow().getFjordServerSshAccess()).isNull();
    }

    @Test
    void load_smtpFieldsAreNullWhenNotPresent() {
        FjordConfig config = FjordConfig.builder()
            .domain("example.com")
            .acmeEmail("admin@example.com")
            .build();

        FjordConfigFileAdapter adapterInstance = adapter();
        adapterInstance.save(config);

        Optional<FjordConfig> loaded = adapter().load();

        assertThat(loaded).isPresent();
        assertThat(loaded.get().getSmtpHost()).isNull();
        assertThat(loaded.get().getSmtpPort()).isNull();
        assertThat(loaded.get().getSmtpUsername()).isNull();
        assertThat(loaded.get().getSmtpSender()).isNull();
    }
}
