package net.fjordomatic.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.fjordomatic.adapter.driven.InMemoryDiskPressureStateAdapter;
import net.fjordomatic.adapter.driven.InMemoryMachineDiskStandingCache;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.port.ForPersistingDiskPressureState;
import net.fjordomatic.application.GetDiskWatchesUseCase;
import net.fjordomatic.application.GetHostCredentialUseCase;
import net.fjordomatic.application.DetectMachineNetworksUseCase;
import net.fjordomatic.application.ForgetMachineNetworksUseCase;
import net.fjordomatic.application.GetMachinesUseCase;
import net.fjordomatic.application.NotifyAdminsOfDiskFillForecastUseCase;
import net.fjordomatic.application.NotifyAdminsOfRemoteDiskPressureUseCase;
import net.fjordomatic.application.RunRemoteCommandUseCase;
import net.fjordomatic.config.ConfigResolver;
import net.fjordomatic.domain.AuthMethod;
import net.fjordomatic.domain.CommandResult;
import net.fjordomatic.domain.DeviceCategory;
import net.fjordomatic.domain.DiskFillForecast;
import net.fjordomatic.domain.DiskFillForecastCleared;
import net.fjordomatic.domain.DiskWatch;
import net.fjordomatic.domain.DiskWatches;
import net.fjordomatic.domain.HostCredentialView;
import net.fjordomatic.domain.Machine;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.NoSshServerException;
import net.fjordomatic.domain.DockerCommandAccess;
import net.fjordomatic.domain.RemoteDiskUsage;
import net.fjordomatic.domain.SshServerPresence;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.domain.port.ForRecordingDockerCommandAccess;
import net.fjordomatic.domain.port.ForRecordingSshServerPresence;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteDiskWatcherTest {

    /** The two SSE events this watcher publishes on the fleet stream, named so assertions can tell them apart. */
    private static final String SSH_PRESENCE_EVENT = "ssh-server-presence-changed";
    private static final String DISK_STANDING_EVENT = "disk-standing-changed";

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    GetMachinesUseCase machines;
    GetHostCredentialUseCase credentials;
    RunRemoteCommandUseCase runner;
    NotifyAdminsOfRemoteDiskPressureUseCase notifier;
    NotifyAdminsOfDiskFillForecastUseCase forecastNotifier;
    GetDiskWatchesUseCase diskWatches;
    ConfigResolver configResolver;
    ForRecordingSshServerPresence sshPresenceRecorder;
    DetectMachineNetworksUseCase detectMachineNetworks;
    ForgetMachineNetworksUseCase forgetMachineNetworks;
    ForPublishingEvents eventPublisher;
    ForPersistingDiskPressureState pressureState;
    InMemoryMachineDiskStandingCache standings;
    ForRecordingDockerCommandAccess dockerAccessRecorder;
    SteppableClock clock;
    RemoteDiskWatcher watcher;

    /** A clock the test advances by hand, so a rising df series can be fed across deterministic polls. */
    static final class SteppableClock extends Clock {
        private Instant now = Instant.parse("2026-07-08T00:00:00Z");

        void advance(Duration by) { now = now.plus(by); }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @BeforeEach
    void setUp() {
        machines = mock(GetMachinesUseCase.class);
        credentials = mock(GetHostCredentialUseCase.class);
        runner = mock(RunRemoteCommandUseCase.class);
        notifier = mock(NotifyAdminsOfRemoteDiskPressureUseCase.class);
        forecastNotifier = mock(NotifyAdminsOfDiskFillForecastUseCase.class);
        diskWatches = mock(GetDiskWatchesUseCase.class);
        configResolver = mock(ConfigResolver.class);
        sshPresenceRecorder = mock(ForRecordingSshServerPresence.class);
        detectMachineNetworks = mock(DetectMachineNetworksUseCase.class);
        forgetMachineNetworks = mock(ForgetMachineNetworksUseCase.class);
        eventPublisher = mock(ForPublishingEvents.class);
        pressureState = new InMemoryDiskPressureStateAdapter();
        standings = new InMemoryMachineDiskStandingCache();
        dockerAccessRecorder = mock(ForRecordingDockerCommandAccess.class);
        clock = new SteppableClock();
        // Nothing configured: every filesystem is watched at the global threshold (#325).
        lenient().when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of()));
        when(configResolver.getDiskMonitorThresholdPercent()).thenReturn(85);
        watcher = newWatcher();
    }

    /** A watcher over the shared mocks and the shared pressure store — a fresh one models a redeploy. */
    private RemoteDiskWatcher newWatcher() {
        return new RemoteDiskWatcher(machines, credentials, runner, notifier, forecastNotifier,
            diskWatches, configResolver, clock, sshPresenceRecorder, eventPublisher, pressureState,
            detectMachineNetworks, forgetMachineNetworks, standings, dockerAccessRecorder);
    }

    /** An SSH-capable server-type machine (effectiveSshAccess() true by default). */
    /** A machine whose id is the stable test id for its name, so watches keyed to it actually match. */
    private Machine sshMachine(String name) {
        return new Machine(mid(name), name, MachineType.UBUNTU_SERVER, null, null, null, null, null, null, null,
            null, "10.13.13.9", false, null, DeviceCategory.SERVER, null);
    }

    private void hasCredential(String name) {
        when(credentials.getHostCredential(mid(name))).thenReturn(
            Optional.of(new HostCredentialView(mid(name), "root", AuthMethod.PASSWORD, true, false)));
    }

    private CommandResult df(int usedPercent) {
        String out = "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
            + "/dev/root 100 " + usedPercent + " " + (100 - usedPercent) + " " + usedPercent + "% /\n";
        return new CommandResult(0, out, "", false, "SHA256:abc");
    }

    @Test
    void crossingIntoPressure_alertsAdmins() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        when(runner.run(eq(mid("nas")), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage(); // baseline below

        when(runner.run(eq(mid("nas")), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage(); // crosses above

        verify(notifier).notifyAdminsOfRemoteDiskPressure(any(RemoteDiskUsage.class), eq(85));
        verify(notifier, never()).notifyAdminsOfRemoteDiskRecovery(any(), anyInt());
    }

    @Test
    void stayingAboveThreshold_doesNotReAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        when(runner.run(eq(mid("nas")), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq(mid("nas")), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage(); // one alert
        watcher.checkRemoteDiskUsage(); // still above, no new alert

        verify(notifier, times(1)).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void droppingBackBelowThreshold_sendsRecovery() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        when(runner.run(eq(mid("nas")), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage(); // baseline above
        when(runner.run(eq(mid("nas")), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage(); // crosses below

        verify(notifier).notifyAdminsOfRemoteDiskRecovery(any(RemoteDiskUsage.class), eq(85));
    }

    @Test
    void machineWithoutCredential_isSkipped_neverRuns() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        when(credentials.getHostCredential(mid("nas"))).thenReturn(Optional.empty());

        watcher.checkRemoteDiskUsage();

        verify(runner, never()).run(any(), any());
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void machineWithSshAccessOff_isSkipped_neverRuns() {
        // sshAccessOverride = false forces effectiveSshAccess() false
        Machine off = new Machine(MachineId.generate(), "printer", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
            null, "192.168.1.111", false, null, DeviceCategory.SERVER, false);
        when(machines.getAllMachines()).thenReturn(List.of(off));

        watcher.checkRemoteDiskUsage();

        verify(credentials, never()).getHostCredential(any());
        verify(runner, never()).run(any(), any());
    }

    @Test
    void execFailure_timedOut_doesNotAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq(mid("nas")), any()))
            .thenReturn(new CommandResult(-1, "", "", true, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
        verify(notifier, never()).notifyAdminsOfRemoteDiskRecovery(any(), anyInt());
    }

    @Test
    void execFailure_nonZeroExit_doesNotAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq(mid("nas")), any()))
            .thenReturn(new CommandResult(127, "", "df: not found", false, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void execFailure_unparseableOutput_doesNotAlert() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq(mid("nas")), any()))
            .thenReturn(new CommandResult(0, "totally not df output", "", false, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void runnerThrowing_doesNotPropagate_andSkipsMachine() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq(mid("nas")), any())).thenThrow(new RuntimeException("unreachable"));

        org.assertj.core.api.Assertions.assertThatCode(() -> watcher.checkRemoteDiskUsage())
            .doesNotThrowAnyException();
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void risingSeriesAcrossPolls_notifiesForecastOnce_whileStillBelowLevel() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // A steady 1%/h climb, well below the 85 level: 74 → 75 → 76 (runway 24h) → 77 (runway 23h, crosses).
        int[] series = {74, 75, 76, 77, 78};
        for (int used : series) {
            when(runner.run(eq(mid("nas")), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        verify(forecastNotifier, times(1)).notifyAdminsOfDiskFillForecast(any(DiskFillForecast.class));
        // Never a level alert — the disk stayed below the disk-pressure threshold throughout.
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void drainingBelowThreshold_afterWarning_sendsAllClearWithCurrentPercent() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // Climb below the level threshold until the early warning fires...
        for (int used : new int[]{74, 75, 76, 77}) {
            when(runner.run(eq(mid("nas")), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }
        // ...then space is freed (sharp drop) while still below threshold → genuine recovery.
        when(runner.run(eq(mid("nas")), any())).thenReturn(df(50));
        watcher.checkRemoteDiskUsage();

        org.mockito.ArgumentCaptor<DiskFillForecastCleared> cleared =
            org.mockito.ArgumentCaptor.forClass(DiskFillForecastCleared.class);
        verify(forecastNotifier).notifyAdminsOfDiskFillForecastCleared(cleared.capture());
        org.assertj.core.api.Assertions.assertThat(cleared.getValue().currentPercent()).isEqualTo(50);
        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void climbingPastThreshold_afterWarning_suppressesForecastClear_onlyPressureAlerts() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // Climb below threshold until the early warning fires...
        for (int used : new int[]{74, 75, 76, 77}) {
            when(runner.run(eq(mid("nas")), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }
        // ...then it crosses the level threshold → the disk-pressure alert speaks; the forecast clear
        // must be suppressed so admins aren't double-paged at the same poll.
        when(runner.run(eq(mid("nas")), any())).thenReturn(df(90));
        watcher.checkRemoteDiskUsage();

        verify(notifier).notifyAdminsOfRemoteDiskPressure(any(RemoteDiskUsage.class), eq(85));
        verify(forecastNotifier, never()).notifyAdminsOfDiskFillForecastCleared(any());
    }

    @Test
    void failedPoll_recordsNoSample_soNoForecastFromBadReadings() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");

        // Every poll fails; a failed df must record no sample, so a forecast can never form.
        when(runner.run(eq(mid("nas")), any()))
            .thenReturn(new CommandResult(-1, "", "", true, "SHA256:abc"));
        for (int i = 0; i < 5; i++) {
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        verify(forecastNotifier, never()).notifyAdminsOfDiskFillForecast(any());
    }

    @Test
    void usesTheDomainsDfCommand_whichReadsEveryFilesystem_notJustRoot() {
        // #325: it used to be `df -P /`. That is the bug — the filesystem that matters is very often not
        // the root one. The command lives on RemoteDiskUsage, next to the parser that reads it.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        lenient().when(runner.run(eq(mid("nas")), any())).thenReturn(df(10));

        watcher.checkRemoteDiskUsage();

        // Ends with the domain's command, rather than being it: the Docker probe (#352) rides in front on
        // the same connection. What #325 is about is unchanged — the df that runs is still unscoped, so it
        // still reads every filesystem and not just the root one.
        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(runner).run(eq(mid("nas")), command.capture());
        assertThat(command.getValue()).endsWith("df -P");
        assertThat(RemoteDiskUsage.DF_COMMAND).isEqualTo("df -P");
    }

    // --- every watched filesystem, not just the root one (#325) -----------------------------------------
    //
    // The NAS is the whole reason this issue exists. Its / is the 2.3 GB DSM system partition — 88% by
    // design, and it never moves — so Fjord alerted about a partition the operator could not act on, while
    // /volume1 (11.6 TB, every borg backup) was invisible and could have filled to 100% in silence.

    /** The real `df -P` from the NAS, with /volume1 driven to `volume1Percent`. / stays at its usual 88%. */
    private CommandResult nasDf(int volume1Percent) {
        return nasDf(88, volume1Percent);
    }

    /** The real `df -P` from the NAS, with / and /volume1 each driven where the test needs them. */
    private CommandResult nasDf(int rootPercent, int volume1Percent) {
        long size = 11614435576L;
        long used = size / 100 * volume1Percent;
        String out = "Filesystem             1024-blocks       Used  Available Capacity Mounted on\n"
            + "/dev/md0                   2385528    1988940     277804      " + rootPercent + "% /\n"
            + "tmpfs                      2021044       1988    2019056       1% /tmp\n"
            + "/dev/mapper/cachedev_0   115404288     512932  114875740       1% /volume2\n"
            + "/dev/mapper/cachedev_1 " + size + " " + used + " " + (size - used) + " "
            + volume1Percent + "% /volume1\n"
            + "none                   " + size + " " + used + " " + (size - used) + " "
            + volume1Percent + "% /volume1/@docker/aufs/mnt/b5720e8\n";
        return new CommandResult(0, out, "", false, "SHA256:abc");
    }

    @Test
    void theVolumeThatUsedToBeInvisible_nowAlerts() {
        // /volume1 filling is precisely what Fjord could never say a word about.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", true, 95))));      // the DSM system partition, given its own threshold

        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();                   // baseline: nothing breaches
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(91));
        watcher.checkRemoteDiskUsage();                   // /volume1 crosses

        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier).notifyAdminsOfRemoteDiskPressure(alerted.capture(), eq(85));
        assertThat(alerted.getValue().mountPoint()).isEqualTo("/volume1");
        assertThat(alerted.getValue().usedPercent()).isEqualTo(91);
    }

    @Test
    void theAlertNamesTheMountAndItsSize_soTheNumberMeansSomething() {
        // "NAS is at 88%" told the operator nothing they could act on — they checked DSM, found the disk
        // nowhere near full, and rightly stopped trusting Fjord.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", true, 95))));

        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(91));
        watcher.checkRemoteDiskUsage();

        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier).notifyAdminsOfRemoteDiskPressure(alerted.capture(), eq(85));
        assertThat(alerted.getValue().pressureSubject())
            .contains("NAS").contains("/volume1").contains("91%").contains("TiB");
    }

    @Test
    void aFilesystemWithItsOwnThreshold_isJudgedAgainstIt_notTheGlobalOne() {
        // The NAS's / at 88% would page forever at the global 85%. Its own 95% threshold keeps it quiet —
        // and still watched, so a genuinely full system partition still speaks.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", true, 95))));

        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void aMutedFilesystem_neverAlerts_howeverFull() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", false, null),
            new DiskWatch(mid("NAS"), "/volume1", false, null),
            new DiskWatch(mid("NAS"), "/volume2", false, null))));

        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(20));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(100));
        watcher.checkRemoteDiskUsage();

        verify(notifier, never()).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void anUnconfiguredFilesystem_isWatched_neverSilentlyIgnored() {
        // The default is watched, and that is not an accident: the failure being fixed IS silence about the
        // disk that matters, so a mount nobody has configured nags rather than hides.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        // No watches at all — /volume1 has never been configured. It is watched anyway, at the global 85%.
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(39));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(91));
        watcher.checkRemoteDiskUsage();                   // /volume1 crosses

        // Two alerts, and the first one is the point of the whole fix: the NAS's unconfigured / is already
        // at 88% against the global 85% on the very first sweep. There is no "crossing" to wait for — it was
        // over the line before Fjord ever looked — so it must speak the first time it is seen, not never.
        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier, times(2)).notifyAdminsOfRemoteDiskPressure(alerted.capture(), eq(85));
        assertThat(alerted.getAllValues()).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/", "/volume1");
    }

    @Test
    void twoFilesystemsOnOneMachine_crossIndependently() {
        // The pressure tracker is keyed on machine AND mount now. Keyed on machine alone, /volume1 crossing
        // would be swallowed by / already being above — the second disk would never be heard.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");

        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(50, 39));
        watcher.checkRemoteDiskUsage();     // baseline: both below
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(90, 39));
        watcher.checkRemoteDiskUsage();     // / crosses above → alerts
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(90, 91));
        watcher.checkRemoteDiskUsage();     // /volume1 crosses above → must ALSO alert

        // Keyed on the machine alone, the tracker would already be "in pressure" from / and would swallow
        // /volume1's crossing as "no change" — the disk that matters would never be heard.
        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier, times(2)).notifyAdminsOfRemoteDiskPressure(alerted.capture(), anyInt());
        assertThat(alerted.getAllValues()).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/", "/volume1");
    }

    @Test
    void theForecastIsKeptPerFilesystem_notPerMachine() {
        // The forecast tracker is keyed on machine AND mount too. /volume1 climbing must forecast on its own
        // trend — a flat / on the same machine must not dilute or mask it.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", true, 95))));      // keep / quiet so only /volume1 can speak

        // /volume1 climbs 1%/h toward full while / sits at its usual 88%.
        for (int used : new int[]{74, 75, 76, 77, 78}) {
            when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        ArgumentCaptor<DiskFillForecast> forecast = ArgumentCaptor.forClass(DiskFillForecast.class);
        verify(forecastNotifier, times(1)).notifyAdminsOfDiskFillForecast(forecast.capture());
        assertThat(forecast.getValue().mountPoint()).isEqualTo("/volume1");
        assertThat(forecast.getValue().machineName()).isEqualTo("NAS");
    }

    @Test
    void aMutedFilesystem_isNotForecastEither() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");
        when(diskWatches.getDiskWatches()).thenReturn(new DiskWatches(List.of(
            new DiskWatch(mid("NAS"), "/", false, null),
            new DiskWatch(mid("NAS"), "/volume1", false, null),
            new DiskWatch(mid("NAS"), "/volume2", false, null))));

        for (int used : new int[]{74, 75, 76, 77, 78}) {
            when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(used));
            watcher.checkRemoteDiskUsage();
            clock.advance(Duration.ofHours(1));
        }

        verify(forecastNotifier, never()).notifyAdminsOfDiskFillForecast(any());
    }

    @Test
    void thePseudoFilesystemsAndTheAufsAliases_areNeverAlertedOn() {
        // The NAS's df carries eight `none` rows, every one an alias of /volume1. Alerting on them would
        // page the operator nine times for one volume.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("NAS")));
        hasCredential("NAS");

        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(50, 39));
        watcher.checkRemoteDiskUsage();
        when(runner.run(eq(mid("NAS")), any())).thenReturn(nasDf(50, 91));
        watcher.checkRemoteDiskUsage();

        // /volume1 crosses once. Its eight aufs aliases carry the identical reading — if they were real
        // filesystems the operator would be paged nine times for one volume.
        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier, times(1)).notifyAdminsOfRemoteDiskPressure(alerted.capture(), anyInt());
        assertThat(alerted.getAllValues()).extracting(RemoteDiskUsage::mountPoint)
            .containsExactly("/volume1")
            .doesNotContain("/tmp")
            .noneMatch(mount -> mount.startsWith("/volume1/@docker"));
    }

    // --- SSH server presence (#341-adjacent): the same sweep, not a second SSH round-trip ------------------
    //
    // A refused connect (NoSshServerException, thrown from the one shared SshConnector) is the one signal
    // narrow enough to record without risking a false positive — a timeout or an auth failure could just as
    // easily mean the machine is asleep. So only that exception moves the tracker; every other failure the
    // sweep already tolerates (timeout, non-zero exit, unparseable output, a generic RuntimeException) leaves
    // it untouched.

    @Test
    void noSshServer_recordsAbsent() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenThrow(new NoSshServerException("kitchen", 22));

        watcher.checkRemoteDiskUsage();

        verify(sshPresenceRecorder).record(mid("kitchen"), SshServerPresence.ABSENT);
    }

    @Test
    void noSshServer_firstObservation_publishesTheTransition() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenThrow(new NoSshServerException("kitchen", 22));
        when(sshPresenceRecorder.record(mid("kitchen"), SshServerPresence.ABSENT))
            .thenReturn(SshServerPresence.UNKNOWN);

        watcher.checkRemoteDiskUsage();

        verify(eventPublisher).publish(eq("vpn-peers"), eq(SSH_PRESENCE_EVENT), anyString());
    }

    @Test
    void noSshServer_repeatedObservation_doesNotRepublish() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenThrow(new NoSshServerException("kitchen", 22));
        when(sshPresenceRecorder.record(mid("kitchen"), SshServerPresence.ABSENT))
            .thenReturn(SshServerPresence.ABSENT);

        watcher.checkRemoteDiskUsage();

        verify(eventPublisher, never()).publish(any(), eq(SSH_PRESENCE_EVENT), any());
    }

    @Test
    void successfulRun_recordsPresent_regardlessOfDfsOwnExitStatus() {
        // Reaching a CommandResult at all — even a df that itself failed — already proves the SSH session
        // connected and authenticated. The server is present whatever df said.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any()))
            .thenReturn(new CommandResult(127, "", "df: not found", false, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();

        verify(sshPresenceRecorder).record(mid("kitchen"), SshServerPresence.PRESENT);
    }

    @Test
    void successfulRun_recoveringFromAbsent_publishesTheTransition() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(10));
        when(sshPresenceRecorder.record(mid("kitchen"), SshServerPresence.PRESENT))
            .thenReturn(SshServerPresence.ABSENT);

        watcher.checkRemoteDiskUsage();

        verify(eventPublisher).publish(eq("vpn-peers"), eq(SSH_PRESENCE_EVENT), anyString());
    }

    @Test
    void successfulRun_alreadyKnownPresent_doesNotRepublish() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(10));
        when(sshPresenceRecorder.record(mid("kitchen"), SshServerPresence.PRESENT))
            .thenReturn(SshServerPresence.PRESENT);

        watcher.checkRemoteDiskUsage();

        verify(eventPublisher, never()).publish(any(), eq(SSH_PRESENCE_EVENT), any());
    }

    @Test
    void ambiguousFailure_neverTouchesSshPresence() {
        // A timeout, an auth failure, a host-key mismatch — none of them prove SSH is absent, so none of
        // them may flip the tracker either way.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenThrow(new RuntimeException("Connection timed out"));

        watcher.checkRemoteDiskUsage();

        verify(sshPresenceRecorder, never()).record(any(), any());
        verify(eventPublisher, never()).publish(any(), any(), any());
    }

    // --- the fleet's disk standings, retained from the sweep already taken -----------------------------
    //
    // The sweep has judged every filesystem on every reachable machine for as long as the disk alerts have
    // existed, and then dropped the readings on the floor. These pin that it keeps them — and that it keeps
    // them without a second SSH round trip, without speaking every five minutes, and without ever inventing
    // a standing for a machine it could not read.

    @Test
    void theSweep_retainsWhatItAlreadyRead_soAFleetListingNeverHasToAskAMachineAnything() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(40));

        watcher.checkRemoteDiskUsage();

        assertThat(standings.getAll()).singleElement().satisfies(standing -> {
            assertThat(standing.machineId()).isEqualTo(mid("kitchen"));
            assertThat(standing.worstMountPoint()).isEqualTo("/");
            assertThat(standing.worstUsedPercent()).isEqualTo(40);
            assertThat(standing.worstThresholdPercent()).isEqualTo(85);
            assertThat(standing.breachingFilesystems()).isZero();
            assertThat(standing.watchedFilesystems()).isEqualTo(1);
        });
        // One df, for everything this sweep learns. A standing that cost a second command would be the very
        // thing the fleet-wide-df-on-page-load note exists to refuse.
        verify(runner, times(1)).run(eq(mid("kitchen")), anyString());
    }

    @Test
    void aStandingThatMoved_wakesTheExplorerOnTheStreamItAlreadyHolds() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(40), df(91));

        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();

        verify(eventPublisher, times(2)).publish(eq("vpn-peers"), eq(DISK_STANDING_EVENT), anyString());
        assertThat(standings.getAll()).singleElement()
            .satisfies(standing -> assertThat(standing.breachingFilesystems()).isEqualTo(1));
    }

    @Test
    void aStandingThatDidNotMove_wakesNobody_soThisIsNotAFiveMinuteDrumbeat() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(40));

        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();
        watcher.checkRemoteDiskUsage();

        verify(eventPublisher, times(1)).publish(eq("vpn-peers"), eq(DISK_STANDING_EVENT), anyString());
    }

    @Test
    void aDiskItCouldNotRead_leavesNoStanding_becauseAbsenceIsNotHealth() {
        // A df that failed, a machine asleep, a machine with no credential — every one of them must leave
        // the card blank rather than green. This fleet has already been bitten once by absence reading as
        // fine.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any()))
            .thenReturn(new CommandResult(127, "", "df: not found", false, "SHA256:abc"));

        watcher.checkRemoteDiskUsage();

        assertThat(standings.getAll()).isEmpty();
        verify(eventPublisher, never()).publish(any(), eq(DISK_STANDING_EVENT), any());
    }

    @Test
    void aMachineWithNothingLeftToJudge_hasItsStandingForgotten_notLeftStandingOnTheCard() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(91));
        watcher.checkRemoteDiskUsage();
        assertThat(standings.getAll()).hasSize(1);

        // The operator mutes the only filesystem there is. Fjord is no longer judging anything on this
        // machine, so it must stop saying anything about it.
        when(diskWatches.getDiskWatches())
            .thenReturn(new DiskWatches(List.of(new DiskWatch(mid("kitchen"), "/", false, null))));

        watcher.checkRemoteDiskUsage();

        assertThat(standings.getAll()).isEmpty();
        verify(eventPublisher, times(2)).publish(eq("vpn-peers"), eq(DISK_STANDING_EVENT), anyString());
    }

    @Test
    void aMachineThatLeftTheFleet_doesNotKeepItsStanding() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(91));
        watcher.checkRemoteDiskUsage();

        when(machines.getAllMachines()).thenReturn(List.of());
        watcher.checkRemoteDiskUsage();

        assertThat(standings.getAll()).isEmpty();
    }

    @Test
    void skippedMachine_neverTouchesSshPresence() {
        // No SSH access, or no stored credential — the sweep never attempts SSH at all, so it has observed
        // nothing and must not guess.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        when(credentials.getHostCredential(mid("kitchen"))).thenReturn(Optional.empty());

        watcher.checkRemoteDiskUsage();

        verify(sshPresenceRecorder, never()).record(any(), any());
    }

    @Test
    void everySweep_retainsOnlyTheCurrentFleet() {
        // A deleted machine's stale presence must not linger forever in the cache.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(10));

        watcher.checkRemoteDiskUsage();

        verify(sshPresenceRecorder).retainOnly(Set.of(mid("kitchen")));
    }

    // --- the silence this fix is about: already-full at startup, and a latch wiped by every redeploy -------
    //
    // The Fjord server's own root filesystem reached 89% against an 80% threshold and NOT ONE email was
    // ever sent. Two faults compounded: the tracker treated its first observation as a silent baseline, so
    // a filesystem that was already above the threshold could never produce a crossing; and the latch was a
    // plain field on this @Component, wiped by every redeploy — several a day — so every sweep was a first
    // observation forever.

    @Test
    void aFilesystemAlreadyAboveThreshold_onTheVeryFirstSweep_alertsImmediately() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("vaier")));
        hasCredential("vaier");
        when(runner.run(eq(mid("vaier")), any())).thenReturn(df(89));

        watcher.checkRemoteDiskUsage();

        verify(notifier).notifyAdminsOfRemoteDiskPressure(any(RemoteDiskUsage.class), eq(85));
    }

    @Test
    void aRedeploy_doesNotReAlertForAFilesystemAlreadyNotifiedAbout() {
        // The other half of the fix. Alerting on the first observation is only safe because the state now
        // outlives the process — otherwise every redeploy would page the operator about the same disk.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("vaier")));
        hasCredential("vaier");
        when(runner.run(eq(mid("vaier")), any())).thenReturn(df(89));

        watcher.checkRemoteDiskUsage();
        newWatcher().checkRemoteDiskUsage();   // a redeploy: fresh watcher, same persisted state
        newWatcher().checkRemoteDiskUsage();

        verify(notifier, times(1)).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
    }

    @Test
    void aDiskClimbingIntoAHigherBand_alertsAgain() {
        // Escalation by band, not by timer: 85 → 90 → 95 is the disk getting materially worse.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("vaier")));
        hasCredential("vaier");

        for (int used : new int[]{86, 88, 91, 93, 96}) {
            when(runner.run(eq(mid("vaier")), any())).thenReturn(df(used));
            watcher.checkRemoteDiskUsage();
        }

        ArgumentCaptor<RemoteDiskUsage> alerted = ArgumentCaptor.forClass(RemoteDiskUsage.class);
        verify(notifier, times(3)).notifyAdminsOfRemoteDiskPressure(alerted.capture(), anyInt());
        assertThat(alerted.getAllValues()).extracting(RemoteDiskUsage::usedPercent)
            .containsExactly(86, 91, 96);
    }

    @Test
    void aRecoveredDiskThatRefills_alertsAgain() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("vaier")));
        hasCredential("vaier");

        when(runner.run(eq(mid("vaier")), any())).thenReturn(df(91));
        watcher.checkRemoteDiskUsage();                       // alerts at band 90
        when(runner.run(eq(mid("vaier")), any())).thenReturn(df(40));
        watcher.checkRemoteDiskUsage();                       // recovers — the ladder resets
        when(runner.run(eq(mid("vaier")), any())).thenReturn(df(86));
        watcher.checkRemoteDiskUsage();                       // refills → must speak again

        verify(notifier, times(2)).notifyAdminsOfRemoteDiskPressure(any(), anyInt());
        verify(notifier, times(1)).notifyAdminsOfRemoteDiskRecovery(any(), anyInt());
    }

    @Test
    void aSuppressedAlert_saysSoInTheLog_soALatchedSilentDiskIsDiagnosable() {
        // The no-op branch used to log nothing at all, which is why a disk at 89% and total silence looked
        // identical to a disk nobody was watching. An operator must be able to tell them apart from the log.
        Logger watcherLog = (Logger) LoggerFactory.getLogger(RemoteDiskWatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        watcherLog.addAppender(appender);
        try {
            when(machines.getAllMachines()).thenReturn(List.of(sshMachine("vaier")));
            hasCredential("vaier");
            when(runner.run(eq(mid("vaier")), any())).thenReturn(df(89));

            watcher.checkRemoteDiskUsage();   // alerts
            watcher.checkRemoteDiskUsage();   // suppressed — and must say so

            assertThat(appender.list)
                .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("/").contains("89").contains("85"));
        } finally {
            watcherLog.detachAppender(appender);
        }
    }

    // --- detecting the network behind a machine (#333) -------------------------------------------------
    //
    // The sweep already reaches every SSH-accessible, credentialed machine every five minutes, and it
    // already piggybacks SSH-server presence onto that trip. The detected LAN rides the same guards for
    // the same reason: the Explorer's nudges endpoint repaints on every machine pane open, and opening a
    // machine must never cost an SSH round-trip.

    @Test
    void everySweep_detectsTheNetworksOfEveryReachableCredentialedMachine() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("colina")));
        hasCredential("colina");
        when(runner.run(eq(mid("colina")), any())).thenReturn(df(10));

        watcher.checkRemoteDiskUsage();

        verify(detectMachineNetworks).detectMachineNetworks(mid("colina"));
    }

    @Test
    void aMachineWithoutSshAccessOrACredential_isNeverAsked() {
        // The acceptance criterion in prose: a machine Fjord cannot read is simply not nudged. It is the
        // same guard that keeps the sweep from mounting a failed-auth storm.
        Machine off = new Machine(MachineId.generate(), "printer", MachineType.LAN_SERVER, null, null, null,
            null, null, null, null, null, "192.168.1.111", false, null, DeviceCategory.SERVER, false);
        when(machines.getAllMachines()).thenReturn(List.of(off, sshMachine("nas")));
        when(credentials.getHostCredential(mid("nas"))).thenReturn(Optional.empty());

        watcher.checkRemoteDiskUsage();

        verify(detectMachineNetworks, never()).detectMachineNetworks(any());
    }

    @Test
    void aNetworkReadThatBlowsUp_neverStopsTheDiskSweep() {
        // Two questions on one trip, and they must not be able to take each other down: a machine whose
        // networks cannot be read still gets its disks judged, and the SSH-server presence its df earned
        // is not withdrawn by a later failure.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("nas")));
        hasCredential("nas");
        when(runner.run(eq(mid("nas")), any())).thenReturn(df(99));
        when(detectMachineNetworks.detectMachineNetworks(mid("nas")))
            .thenThrow(new RuntimeException("connection reset"));

        watcher.checkRemoteDiskUsage();

        verify(notifier).notifyAdminsOfRemoteDiskPressure(any(RemoteDiskUsage.class), eq(85));
        verify(sshPresenceRecorder).record(mid("nas"), SshServerPresence.PRESENT);
    }

    @Test
    void everySweep_forgetsTheNetworksOfMachinesTheFleetNoLongerHas() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("kitchen")));
        hasCredential("kitchen");
        when(runner.run(eq(mid("kitchen")), any())).thenReturn(df(10));

        watcher.checkRemoteDiskUsage();

        verify(forgetMachineNetworks).forgetMachineNetworksExcept(Set.of(mid("kitchen")));
    }

    // --- what else this trip learns: the machine's Docker access (#352) ---

    /** A sweep reading with the Docker probe's marker line in front of df's own output. */
    private CommandResult sweptWithDockerRc(int rc, int usedPercent) {
        CommandResult disks = df(usedPercent);
        return new CommandResult(disks.exitCode(), "VAIER-DOCKER-RC=" + rc + "\n" + disks.stdout(),
            disks.stderr(), false, disks.hostKeyFingerprint());
    }

    @Test
    void theDockerProbeRidesOnTheTripThisSweepAlreadyMakes_withoutASecondSignIn() {
        // The point of learning it here: one sign-in per machine per sweep, not two. A machine's Docker
        // access is worth knowing, but not worth another SSH connection to every machine every 5 minutes.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("colina27")));
        hasCredential("colina27");
        when(runner.run(eq(mid("colina27")), any())).thenReturn(sweptWithDockerRc(0, 40));

        watcher.checkRemoteDiskUsage();

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(runner, times(1)).run(eq(mid("colina27")), command.capture());
        assertThat(command.getValue()).contains("docker version").endsWith(RemoteDiskUsage.DF_COMMAND);
    }

    @Test
    void aMachineWhoseSshUserCannotDriveDocker_isRecordedAsRefused() {
        // Colina 27: geir is not in that host's docker group, so every compose command an update would
        // run there dies on permission denied — while the container scrape, which uses the Docker API over
        // the tunnel, shows the machine as perfectly healthy.
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("colina27")));
        hasCredential("colina27");
        when(runner.run(eq(mid("colina27")), any())).thenReturn(sweptWithDockerRc(1, 40));

        watcher.checkRemoteDiskUsage();

        verify(dockerAccessRecorder).record(mid("colina27"), DockerCommandAccess.REFUSED);
    }

    @Test
    void aMachineWhoseSshUserCanDriveDocker_isRecordedAsGranted() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("apalveien5")));
        hasCredential("apalveien5");
        when(runner.run(eq(mid("apalveien5")), any())).thenReturn(sweptWithDockerRc(0, 40));

        watcher.checkRemoteDiskUsage();

        verify(dockerAccessRecorder).record(mid("apalveien5"), DockerCommandAccess.GRANTED);
    }

    @Test
    void aTripThatCameBackWithoutTheMarker_recordsNothing_soAKnownFactSurvivesASilentSweep() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("colina27")));
        hasCredential("colina27");
        when(runner.run(eq(mid("colina27")), any())).thenReturn(df(40));   // no marker at all

        watcher.checkRemoteDiskUsage();

        verify(dockerAccessRecorder, never()).record(any(), any());
    }

    @Test
    void aMachineThatLeftTheFleet_hasItsDockerAccessForgottenToo() {
        when(machines.getAllMachines()).thenReturn(List.of(sshMachine("colina27")));
        hasCredential("colina27");
        when(runner.run(eq(mid("colina27")), any())).thenReturn(sweptWithDockerRc(1, 40));

        watcher.checkRemoteDiskUsage();

        verify(dockerAccessRecorder).retainOnly(Set.of(mid("colina27")));
    }
}
