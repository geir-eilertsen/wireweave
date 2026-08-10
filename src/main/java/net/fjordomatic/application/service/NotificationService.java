package net.fjordomatic.application.service;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.NotifyAdminsOfBackupFailureUseCase;
import net.fjordomatic.application.NotifyAdminsOfBackupServerDownUseCase;
import net.fjordomatic.application.NotifyAdminsOfBreachAttemptUseCase;
import net.fjordomatic.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.fjordomatic.application.NotifyAdminsOfLockoutWarningUseCase;
import net.fjordomatic.application.NotifyAdminsOfPeerTransitionUseCase;
import net.fjordomatic.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.fjordomatic.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.fjordomatic.config.ConfigResolver;
import net.fjordomatic.domain.BackupRun;
import net.fjordomatic.domain.BackupServer;
import net.fjordomatic.domain.BreachAttemptRollup;
import net.fjordomatic.domain.DiskFillForecast;
import net.fjordomatic.domain.DiskFillForecastCleared;
import net.fjordomatic.domain.ImageUpdateRollup;
import net.fjordomatic.domain.LockoutWarning;
import net.fjordomatic.domain.RemoteDiskUsage;
import net.fjordomatic.domain.PeerSnapshot;
import net.fjordomatic.domain.port.ForProbingTcp.ProbeResult;
import net.fjordomatic.domain.port.ForSendingAdminNotification;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class NotificationService implements
        NotifyAdminsOfPeerTransitionUseCase,
        NotifyAdminsOfRemoteDiskPressureUseCase,
        NotifyAdminsOfDiskFillForecastUseCase,
        NotifyAdminsOfBackupFailureUseCase,
        NotifyAdminsOfBackupServerDownUseCase,
        NotifyAdminsOfUpdateAvailableUseCase,
        NotifyAdminsOfBreachAttemptUseCase,
        NotifyAdminsOfLockoutWarningUseCase {

    private final ForSendingAdminNotification adminNotifier;
    private final ConfigResolver configResolver;

    public NotificationService(ForSendingAdminNotification adminNotifier,
                               ConfigResolver configResolver) {
        this.adminNotifier = adminNotifier;
        this.configResolver = configResolver;
    }

    @Override
    public void notifyAdmins(PeerSnapshot snapshot) {
        adminNotifier.sendToAdmins(snapshot.notificationSubject(),
                snapshot.notificationBody(configResolver.getDomain()),
                "peer " + snapshot.name());
    }

    @Override
    public void notifyAdminsOfRemoteDiskPressure(RemoteDiskUsage usage, int thresholdPercent) {
        adminNotifier.sendToAdmins(usage.pressureSubject(),
                usage.pressureBody(thresholdPercent, configResolver.getDomain()),
                "remote disk pressure on " + usage.machineName());
    }

    @Override
    public void notifyAdminsOfRemoteDiskRecovery(RemoteDiskUsage usage, int thresholdPercent) {
        adminNotifier.sendToAdmins(usage.recoverySubject(),
                usage.pressureBody(thresholdPercent, configResolver.getDomain()),
                "remote disk recovery on " + usage.machineName());
    }

    @Override
    public void notifyAdminsOfDiskFillForecast(DiskFillForecast forecast) {
        adminNotifier.sendToAdmins(forecast.forecastSubject(),
                forecast.forecastBody(configResolver.getDomain()),
                "disk-fill forecast on " + forecast.machineName());
    }

    @Override
    public void notifyAdminsOfDiskFillForecastCleared(DiskFillForecastCleared cleared) {
        adminNotifier.sendToAdmins(cleared.clearedSubject(),
                cleared.clearedBody(configResolver.getDomain()),
                "disk-fill forecast cleared on " + cleared.machineName());
    }

    @Override
    public void notifyAdminsOfBackupFailure(BackupRun run, String machineLabel) {
        adminNotifier.sendToAdmins(run.failureSubject(machineLabel),
                run.failureBody(machineLabel, configResolver.getDomain()),
                "backup failure for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupRecovery(BackupRun run, String machineLabel) {
        adminNotifier.sendToAdmins(run.recoverySubject(machineLabel),
                run.recoveryBody(machineLabel, configResolver.getDomain()),
                "backup recovery for job " + run.jobName());
    }

    @Override
    public void notifyAdminsOfBackupServerDown(BackupServer server, String machineLabel, ProbeResult cause) {
        adminNotifier.sendToAdmins(server.downSubject(),
                server.downBody(machineLabel, configResolver.getDomain(), cause),
                "backup server down: " + server.name());
    }

    @Override
    public void notifyAdminsOfBackupServerRecovered(BackupServer server, String machineLabel) {
        adminNotifier.sendToAdmins(server.recoverySubject(),
                server.recoveryBody(machineLabel, configResolver.getDomain()),
                "backup server recovery: " + server.name());
    }

    /**
     * One rollup mail for the images that just became out of date. The rollup renders itself — subject, body
     * and the "Fjord does not pull" line are the domain's words, not this service's; it only sequences the send.
     */
    @Override
    public void notifyAdminsOfUpdateAvailable(ImageUpdateRollup rollup) {
        adminNotifier.sendToAdmins(rollup.subject(),
                rollup.body(configResolver.getDomain()),
                "update available for " + rollup.images().size() + " image(s)");
    }

    /**
     * One rollup mail for the block decisions that just appeared. The rollup renders itself; this
     * service only sequences the send.
     */
    @Override
    public void notifyAdminsOfBreachAttempt(BreachAttemptRollup rollup) {
        adminNotifier.sendToAdmins(rollup.subject(),
                rollup.body(configResolver.getDomain()),
                "breach attempt: " + rollup.decisions().size() + " new block decision(s)");
    }

    /**
     * One warning mail for the operator's own addresses that CrowdSec has started blocking. Its own
     * subject and body come from the domain — this is not a breach attempt and must not read like one.
     */
    @Override
    public void notifyAdminsOfLockoutWarning(LockoutWarning warning) {
        adminNotifier.sendToAdmins(warning.subject(),
                warning.body(configResolver.getDomain()),
                "lockout warning: " + warning.decisions().size() + " trusted address(es) blocked");
    }

}
