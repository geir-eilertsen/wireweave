package net.fjordomatic.application;

/**
 * Update the operator-tunable fleet-backup scheduling preferences. Narrow, one method per setting —
 * mirrors {@link UpdateDiskMonitorSettingsUseCase}. Implemented by {@code SettingsService} (the settings
 * domain owns Fjord's config), not by {@code BackupRestController}: the nightly schedule hour is a
 * plain preference like the disk-alert threshold, so it is exposed via {@code /settings}.
 */
public interface UpdateBackupSettingsUseCase {

    /** Set the hour of day (0–23) at which nightly scheduling fires due jobs; rejects out-of-range hours. */
    void updateBackupScheduleHour(int hour);
}
