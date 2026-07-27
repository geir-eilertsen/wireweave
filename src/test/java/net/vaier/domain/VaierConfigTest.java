package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaierConfigTest {

    private static VaierConfig fullConfig() {
        return VaierConfig.builder()
            .domain("vaier.net")
            .awsKey("AKIAEXAMPLEKEY12345")
            .awsSecret("topsecret")
            .acmeEmail("ops@vaier.net")
            .smtpHost("smtp.example.com")
            .smtpPort(587)
            .smtpUsername("mailer")
            .smtpSender("noreply@vaier.net")
            .build();
    }

    @Test
    void maskedAwsKey_masksAllButLastFourCharacters() {
        assertThat(fullConfig().maskedAwsKey()).isEqualTo("****2345");
    }

    @Test
    void maskedAwsKey_returnsShortKeyUnchanged() {
        VaierConfig config = VaierConfig.builder().awsKey("ABCD").build();

        assertThat(config.maskedAwsKey()).isEqualTo("ABCD");
    }

    @Test
    void maskedAwsKey_returnsNullWhenKeyAbsent() {
        assertThat(VaierConfig.builder().build().maskedAwsKey()).isNull();
    }

    @Test
    void effectiveDiskMonitorThresholdPercent_defaultsTo85WhenUnset() {
        assertThat(VaierConfig.builder().build().effectiveDiskMonitorThresholdPercent()).isEqualTo(85);
    }

    @Test
    void effectiveDiskMonitorThresholdPercent_usesConfiguredValue() {
        VaierConfig config = VaierConfig.builder().diskMonitorThresholdPercent(70).build();

        assertThat(config.effectiveDiskMonitorThresholdPercent()).isEqualTo(70);
    }

    @Test
    void withDiskMonitorThreshold_replacesThresholdAndKeepsOtherFields() {
        VaierConfig updated = fullConfig().withDiskMonitorThreshold(60);

        assertThat(updated.getDiskMonitorThresholdPercent()).isEqualTo(60);
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getSmtpHost()).isEqualTo("smtp.example.com");
    }

    @Test
    void withDiskMonitorThreshold_rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> fullConfig().withDiskMonitorThreshold(0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fullConfig().withDiskMonitorThreshold(100))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backupScheduleHourDefaultsAndValidatesRange() {
        // Defaults to 2am when unset.
        assertThat(VaierConfig.builder().build().effectiveBackupScheduleHour()).isEqualTo(2);
        // Uses the configured value when present.
        assertThat(VaierConfig.builder().backupScheduleHour(5).build().effectiveBackupScheduleHour())
            .isEqualTo(5);
        // The wither replaces the hour and carries every other field over.
        VaierConfig updated = fullConfig().withBackupScheduleHour(23);
        assertThat(updated.getBackupScheduleHour()).isEqualTo(23);
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getSmtpHost()).isEqualTo("smtp.example.com");
        // 0 and 23 are the valid bounds.
        assertThat(fullConfig().withBackupScheduleHour(0).getBackupScheduleHour()).isEqualTo(0);
        // Out-of-range hours are rejected.
        assertThatThrownBy(() -> fullConfig().withBackupScheduleHour(-1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fullConfig().withBackupScheduleHour(24))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withAwsCredentials_replacesCredentialsAndCarriesEveryOtherFieldOver() {
        VaierConfig updated = fullConfig().withAwsCredentials("AKIANEWKEY999", "newsecret");

        assertThat(updated.getAwsKey()).isEqualTo("AKIANEWKEY999");
        assertThat(updated.getAwsSecret()).isEqualTo("newsecret");
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getAcmeEmail()).isEqualTo("ops@vaier.net");
        assertThat(updated.getSmtpHost()).isEqualTo("smtp.example.com");
        assertThat(updated.getSmtpPort()).isEqualTo(587);
        assertThat(updated.getSmtpUsername()).isEqualTo("mailer");
        assertThat(updated.getSmtpSender()).isEqualTo("noreply@vaier.net");
    }

    @Test
    void withSmtpSettings_replacesSmtpFieldsAndCarriesEveryOtherFieldOver() {
        VaierConfig updated = fullConfig().withSmtpSettings("smtp.new.com", 2525, "newuser", "from@vaier.net", "newpass");

        assertThat(updated.getSmtpHost()).isEqualTo("smtp.new.com");
        assertThat(updated.getSmtpPort()).isEqualTo(2525);
        assertThat(updated.getSmtpUsername()).isEqualTo("newuser");
        assertThat(updated.getSmtpSender()).isEqualTo("from@vaier.net");
        assertThat(updated.getSmtpPassword()).isEqualTo("newpass");
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getAwsKey()).isEqualTo("AKIAEXAMPLEKEY12345");
        assertThat(updated.getAwsSecret()).isEqualTo("topsecret");
        assertThat(updated.getAcmeEmail()).isEqualTo("ops@vaier.net");
    }

    @Test
    void resolveSmtpPassword_prefersTheProvidedPassword() {
        assertThat(VaierConfig.resolveSmtpPassword("fresh", Optional.of("stored")))
            .isEqualTo("fresh");
    }

    @Test
    void resolveSmtpPassword_fallsBackToStoredWhenProvidedIsBlank() {
        assertThat(VaierConfig.resolveSmtpPassword("  ", Optional.of("stored")))
            .isEqualTo("stored");
        assertThat(VaierConfig.resolveSmtpPassword(null, Optional.of("stored")))
            .isEqualTo("stored");
    }

    @Test
    void resolveSmtpPassword_throwsWhenNeitherProvidedNorStored() {
        assertThatThrownBy(() -> VaierConfig.resolveSmtpPassword(null, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SMTP password");
    }

    @Test
    void resolveSmtpPassword_throwsWhenStoredIsBlank() {
        assertThatThrownBy(() -> VaierConfig.resolveSmtpPassword("", Optional.of("   ")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SMTP password");
    }

    @Test
    void isSmtpConfigured_trueWhenHostAndUsernameArePresent() {
        assertThat(fullConfig().isSmtpConfigured()).isTrue();
    }

    @Test
    void isSmtpConfigured_falseWhenHostOrUsernameMissing() {
        assertThat(VaierConfig.builder().smtpUsername("mailer").build().isSmtpConfigured()).isFalse();
        assertThat(VaierConfig.builder().smtpHost("smtp.example.com").build().isSmtpConfigured()).isFalse();
        assertThat(VaierConfig.builder().build().isSmtpConfigured()).isFalse();
    }

    // --- the survival kit passphrase -------------------------------------------------------------------

    @Test
    void withSurvivalKitPassphrase_replacesItAndCarriesEveryOtherFieldOver() {
        VaierConfig updated = fullConfig().withSurvivalKitPassphrase("correct horse battery staple");

        assertThat(updated.getSurvivalKitPassphrase()).isEqualTo("correct horse battery staple");
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getAwsSecret()).isEqualTo("topsecret");
        assertThat(updated.getSmtpHost()).isEqualTo("smtp.example.com");
    }

    /**
     * A kit written with a blank passphrase looks exactly like a protected one and hands the fleet to anyone
     * who opens it, so the emptiness is refused at the point it would be stored — not later, when the only
     * thing left to do about it is write a kit that gives everything away.
     */
    @Test
    void withSurvivalKitPassphrase_refusesABlankOne() {
        assertThatThrownBy(() -> fullConfig().withSurvivalKitPassphrase("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("passphrase");
        assertThatThrownBy(() -> fullConfig().withSurvivalKitPassphrase(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("passphrase");
    }

    /**
     * Asked before every rollout, and answered for the browser — which is told <em>whether</em> there is a
     * passphrase and never what it is.
     */
    @Test
    void hasSurvivalKitPassphrase_isFalseUntilOneIsChosen() {
        assertThat(VaierConfig.builder().build().hasSurvivalKitPassphrase()).isFalse();
        assertThat(VaierConfig.builder().survivalKitPassphrase("  ").build().hasSurvivalKitPassphrase())
            .isFalse();
        assertThat(fullConfig().withSurvivalKitPassphrase("chosen").hasSurvivalKitPassphrase()).isTrue();
    }

    @Test
    void withSurvivalKitFingerprint_recordsWhatWasWrittenAndKeepsEveryOtherField() {
        VaierConfig updated = fullConfig().withSurvivalKitFingerprint("abc123");

        assertThat(updated.getSurvivalKitFingerprint()).isEqualTo("abc123");
        assertThat(updated.getDomain()).isEqualTo("vaier.net");
        assertThat(updated.getAwsSecret()).isEqualTo("topsecret");
    }

    /**
     * The one staleness a fingerprint of the contents cannot see: the kit says exactly the same thing, it is
     * just locked with a different passphrase now. Every copy on the fleet still opens with the old one, so
     * changing the passphrase here forgets what was written — which reads downstream as "never written" and
     * rewrites the fleet on the next sweep.
     */
    @Test
    void changingThePassphraseForgetsWhatWasWritten_becauseTheOldKitsNoLongerOpenWithIt() {
        VaierConfig written = fullConfig()
            .withSurvivalKitPassphrase("the old one")
            .withSurvivalKitFingerprint("abc123");

        VaierConfig rekeyed = written.withSurvivalKitPassphrase("the new one");

        assertThat(rekeyed.getSurvivalKitFingerprint()).isNull();
        assertThat(rekeyed.getSurvivalKitPassphrase()).isEqualTo("the new one");
    }

    // --- the Vaier server's own identity ---------------------------------------------------------------

    /**
     * The Vaier server is neither a peer nor a LAN server, so its {@link MachineId} has nowhere to live but
     * here. Reading it back was being hand-rolled in three places — the id registry, the machine service and
     * (now) the SSH address resolution — each with its own idea of what an unusable value means.
     */
    @Test
    void vaierServerIdentity_readsTheStoredId() {
        VaierConfig config = VaierConfig.builder()
            .vaierServerMachineId("c0355605-e5a0-419a-8943-fdc5ec209958").build();

        assertThat(config.vaierServerIdentity())
            .contains(MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958"));
    }

    @Test
    void vaierServerIdentity_isEmptyWhenNoneHasBeenAssignedYet() {
        assertThat(VaierConfig.builder().build().vaierServerIdentity()).isEmpty();
    }

    /**
     * Empty, never a substitute. An id read, never minted — a config whose id was hand-edited into nonsense
     * must not quietly become a different machine, and deciding to assign a new one is not a read's job.
     */
    @Test
    void vaierServerIdentity_isEmptyWhenTheStoredValueIsUnusable() {
        assertThat(VaierConfig.builder().vaierServerMachineId("1-1-1-1-1").build()
            .vaierServerIdentity()).isEmpty();
        assertThat(VaierConfig.builder().vaierServerMachineId("  ").build()
            .vaierServerIdentity()).isEmpty();
    }
}
