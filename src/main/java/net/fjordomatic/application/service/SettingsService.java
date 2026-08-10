package net.fjordomatic.application.service;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.GetAppSettingsUseCase;
import net.fjordomatic.application.GetAppVersionUseCase;
import net.fjordomatic.application.SetSurvivalKitPassphraseUseCase;
import net.fjordomatic.application.TestSmtpCredentialsUseCase;
import net.fjordomatic.application.UpdateBackupSettingsUseCase;
import net.fjordomatic.application.UpdateDiskMonitorSettingsUseCase;
import net.fjordomatic.application.UpdateSmtpSettingsUseCase;
import net.fjordomatic.config.ConfigResolver;
import net.fjordomatic.config.WildcardDnsStatusHolder;
import net.fjordomatic.domain.FjordConfig;
import net.fjordomatic.domain.WildcardDnsReport;
import net.fjordomatic.domain.port.ForPersistingAppConfiguration;
import net.fjordomatic.domain.port.ForReadingAppVersion;
import net.fjordomatic.domain.port.ForReadingStoredSmtpPassword;
import net.fjordomatic.domain.port.ForSendingTestEmail;
import net.fjordomatic.domain.port.ForVerifyingSmtpCredentials;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@Slf4j
public class SettingsService implements
    GetAppSettingsUseCase,
    GetAppVersionUseCase,
    UpdateSmtpSettingsUseCase,
    UpdateDiskMonitorSettingsUseCase,
    UpdateBackupSettingsUseCase,
    SetSurvivalKitPassphraseUseCase,
    TestSmtpCredentialsUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForVerifyingSmtpCredentials smtpVerifier;
    private final ForReadingStoredSmtpPassword storedPasswordReader;
    private final ForSendingTestEmail testEmailSender;
    private final ConfigResolver configResolver;
    private final WildcardDnsStatusHolder wildcardDnsStatusHolder;
    private final ForReadingAppVersion appVersionReader;
    private final Clock clock;

    public SettingsService(ForPersistingAppConfiguration configPersistence,
                           ForVerifyingSmtpCredentials smtpVerifier,
                           ForReadingStoredSmtpPassword storedPasswordReader,
                           ForSendingTestEmail testEmailSender,
                           ConfigResolver configResolver,
                           WildcardDnsStatusHolder wildcardDnsStatusHolder,
                           ForReadingAppVersion appVersionReader,
                           Clock clock) {
        this.configPersistence = configPersistence;
        this.smtpVerifier = smtpVerifier;
        this.storedPasswordReader = storedPasswordReader;
        this.testEmailSender = testEmailSender;
        this.configResolver = configResolver;
        this.wildcardDnsStatusHolder = wildcardDnsStatusHolder;
        this.appVersionReader = appVersionReader;
        this.clock = clock;
    }

    @Override
    public String appVersion() {
        return appVersionReader.currentVersion();
    }

    @Override
    public AppSettingsResult getSettings() {
        return configPersistence.load()
            .map(this::toResult)
            .orElse(new AppSettingsResult(null, null, null, null, null, null,
                wildcardDnsStatus(), wildcardDnsLabel(), wildcardDnsSeverity(), wildcardDnsMessage(),
                FjordConfig.DEFAULT_DISK_MONITOR_THRESHOLD_PERCENT, configResolver.isSocialAuthAvailable(),
                FjordConfig.DEFAULT_BACKUP_SCHEDULE_HOUR, backupScheduleZone(), false));
    }

    @Override
    public void updateDiskMonitorThreshold(int thresholdPercent) {
        FjordConfig current = configPersistence.load().orElse(FjordConfig.builder().build());
        configPersistence.save(current.withDiskMonitorThreshold(thresholdPercent));
        configResolver.reload();
        log.info("Host disk monitor threshold updated to {}%", thresholdPercent);
    }

    @Override
    public void updateBackupScheduleHour(int hour) {
        FjordConfig current = configPersistence.load().orElse(FjordConfig.builder().build());
        configPersistence.save(current.withBackupScheduleHour(hour));
        configResolver.reload();
        log.info("Fleet-backup nightly schedule hour updated to {}", hour);
    }

    @Override
    public void setSurvivalKitPassphrase(String passphrase) {
        FjordConfig current = configPersistence.load().orElse(FjordConfig.builder().build());
        configPersistence.save(current.withSurvivalKitPassphrase(passphrase));
        // Never logged, not even masked, and never its length: this one opens every backup in the fleet.
        log.info("Survival kit passphrase set");
    }

    /**
     * What the boot-time wildcard check found, as the status name — null before it has run. The verdict
     * is stated where the operator can act on it rather than only in a log line that has scrolled away.
     */
    private String wildcardDnsStatus() {
        return wildcardDnsStatusHolder.report()
            .map(r -> r.status().name())
            .orElse(null);
    }

    /** The verdict in the operator's words, worded by the domain — null before the check has run. */
    private String wildcardDnsLabel() {
        return wildcardDnsStatusHolder.report()
            .map(r -> r.status().getLabel())
            .orElse(null);
    }

    /**
     * How much attention the verdict deserves, as the severity name — null before the check has run.
     * The domain grades it; this only passes the grade on.
     */
    private String wildcardDnsSeverity() {
        return wildcardDnsStatusHolder.report()
            .map(r -> r.status().getSeverity().name())
            .orElse(null);
    }

    /** The same verdict as a sentence, worded by the domain — null before the check has run. */
    private String wildcardDnsMessage() {
        return wildcardDnsStatusHolder.report()
            .map(WildcardDnsReport::message)
            .orElse(null);
    }

    @Override
    public void updateSmtpSettings(String host, int port, String username, String password, String sender) {
        String resolvedPassword = resolveSmtpPassword(password);
        smtpVerifier.verify(host, port, username, resolvedPassword);

        FjordConfig current = configPersistence.load().orElse(FjordConfig.builder().build());
        configPersistence.save(current.withSmtpSettings(host, port, username, sender, resolvedPassword));
        log.info("SMTP settings updated for host: {}", host);
    }

    @Override
    public void sendTestEmail(String host, int port, String username, String password,
                              String sender, String recipient) {
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("recipient email address is required");
        }
        String resolvedPassword = resolveSmtpPassword(password);
        testEmailSender.sendTestEmail(host, port, username, resolvedPassword, sender, recipient);
    }

    private AppSettingsResult toResult(FjordConfig config) {
        return new AppSettingsResult(
            config.getDomain(),
            config.getAcmeEmail(),
            config.getSmtpHost(),
            config.getSmtpPort(),
            config.getSmtpUsername(),
            config.getSmtpSender(),
            wildcardDnsStatus(),
            wildcardDnsLabel(),
            wildcardDnsSeverity(),
            wildcardDnsMessage(),
            config.effectiveDiskMonitorThresholdPercent(),
            configResolver.isSocialAuthAvailable(),
            config.effectiveBackupScheduleHour(),
            backupScheduleZone(),
            config.hasSurvivalKitPassphrase()
        );
    }

    /**
     * The zone the nightly backup schedule fires in, taken from the same clock the scheduler reads, so the
     * label the UI shows can never drift from the hour that actually runs.
     */
    private String backupScheduleZone() {
        return clock.getZone().getId();
    }

    private String resolveSmtpPassword(String provided) {
        return FjordConfig.resolveSmtpPassword(provided, storedPasswordReader.readStoredPassword());
    }
}
