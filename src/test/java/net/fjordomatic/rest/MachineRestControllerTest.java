package net.fjordomatic.rest;

import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.application.ClearHostKeyUseCase;
import net.fjordomatic.application.GetBackupJobsUseCase;
import net.fjordomatic.application.GetBackupRunsUseCase;
import net.fjordomatic.application.GetBackupServersUseCase;
import net.fjordomatic.application.GetHostCredentialUseCase;
import net.fjordomatic.application.GetLanServerReachabilityUseCase;
import net.fjordomatic.application.GetMachineDiskStandingsUseCase;
import net.fjordomatic.application.GetMachineDiskUsageUseCase;
import net.fjordomatic.application.GetMachineDiskUsageUseCase.MachineFilesystemUco;
import net.fjordomatic.application.GetMachineNetworksUseCase;
import net.fjordomatic.application.GetPublishableServicesUseCase;
import net.fjordomatic.application.GetSshServerPresenceUseCase;
import net.fjordomatic.application.SetDiskWatchUseCase;
import net.fjordomatic.application.GetMachinesUseCase;
import net.fjordomatic.application.GetFjordServerUseCase;
import net.fjordomatic.application.SetMachineSshAccessUseCase;
import net.fjordomatic.domain.AuthMethod;
import net.fjordomatic.domain.BackupJob;
import net.fjordomatic.domain.BackupRun;
import net.fjordomatic.domain.BackupServer;
import net.fjordomatic.domain.DeviceCategory;
import net.fjordomatic.domain.HostCredentialView;
import net.fjordomatic.domain.LanAnchor;
import net.fjordomatic.domain.Machine;
import net.fjordomatic.domain.MachineDiskStanding;
import net.fjordomatic.domain.MachineNetworks;
import net.fjordomatic.domain.MachineNudge;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.NotFoundException;
import net.fjordomatic.domain.PublishableService;
import net.fjordomatic.domain.PublishableService.PublishableSource;
import net.fjordomatic.domain.SshServerPresence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MachineRestControllerTest {

    private static net.fjordomatic.domain.MachineId mid(String name) {
        return net.fjordomatic.domain.TestMachineIds.of(name);
    }

    @Mock GetMachinesUseCase getMachinesUseCase;
    @Mock GetFjordServerUseCase getFjordServerUseCase;
    @Mock SetMachineSshAccessUseCase setMachineSshAccessUseCase;
    @Mock GetHostCredentialUseCase getHostCredentialUseCase;
    @Mock ClearHostKeyUseCase clearHostKeyUseCase;
    @Mock GetMachineDiskUsageUseCase getMachineDiskUsageUseCase;
    @Mock GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    @Mock SetDiskWatchUseCase setDiskWatchUseCase;
    @Mock GetPublishableServicesUseCase getPublishableServicesUseCase;
    @Mock GetBackupJobsUseCase getBackupJobsUseCase;
    @Mock GetBackupRunsUseCase getBackupRunsUseCase;
    @Mock GetBackupServersUseCase getBackupServersUseCase;
    @Mock GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    @Mock GetSshServerPresenceUseCase getSshServerPresenceUseCase;
    @Mock GetMachineNetworksUseCase getMachineNetworksUseCase;

    @InjectMocks MachineRestController controller;

    @BeforeEach
    void defaultNoStoredCredential() {
        // list() reads a credential per machine; default every lookup to "none stored" so the tests
        // that don't care about credentials keep asserting only what they set. Tests that do care
        // override the specific name.
        lenient().when(getHostCredentialUseCase.getHostCredential(any(MachineId.class))).thenReturn(Optional.empty());
    }

    @Test
    void list_emptyWhenNothingRegistered() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of());

        assertThat(controller.list()).isEmpty();
    }

    @Test
    void list_returnsMachinesAcrossWgPeerAndLanServer() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(MachineId.generate(), "alice", MachineType.UBUNTU_SERVER,
                "pubkey", "10.13.13.2/32", "1.2.3.4", "51820",
                "1700000000", "100", "200",
                null, null, true, null, net.fjordomatic.domain.DeviceCategory.SERVER, null),
            new Machine(mid("nas"), "nas", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.50", true, 2375, net.fjordomatic.domain.DeviceCategory.NAS, null)
        ));

        var response = controller.list();

        assertThat(response).extracting("name", "type")
            .containsExactly(
                tuple("alice", "UBUNTU_SERVER"),
                tuple("nas", "LAN_SERVER"));
        assertThat(response.get(0).publicKey()).isEqualTo("pubkey");
        assertThat(response.get(1).publicKey()).isNull();
        assertThat(response.get(1).runsDocker()).isTrue();
        assertThat(response.get(1).dockerPort()).isEqualTo(2375);
        assertThat(response.get(0).deviceCategory()).isEqualTo("SERVER");
        assertThat(response.get(1).deviceCategory()).isEqualTo("NAS");
    }

    @Test
    void list_marksWhichMachineIsTheFjordServer() {
        // The browser identified the Fjord server by comparing its display name to the literal string
        // "Fjord server" — in six places. That is a name used as an identity, and it breaks in both
        // directions: rename the host and Fjord stops recognising itself, or let another machine take
        // that name and Fjord mistakes it for itself. The backend already knows which machine it is.
        Machine server = new Machine(mid("vaier"), "Fjord server", MachineType.UBUNTU_SERVER,
            null, null, null, null, null, null, null,
            "172.31.0.0/16", "172.31.5.20", true, null, net.fjordomatic.domain.DeviceCategory.SERVER, null);
        Machine other = new Machine(mid("nas"), "nas", MachineType.LAN_SERVER,
            null, null, null, null, null, null, null,
            "192.168.3.0/24", "192.168.3.50", true, 2375, net.fjordomatic.domain.DeviceCategory.NAS, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(server, other));
        when(getFjordServerUseCase.getFjordServerMachine()).thenReturn(server);

        var response = controller.list();

        assertThat(response.get(0).fjordServer()).isTrue();
        assertThat(response.get(1).fjordServer()).isFalse();
    }

    @Test
    void list_stillReturnsTheFleetWhenTheFjordServerCannotBeResolved() {
        // Identity-keying turned a string comparison into a lookup, and this one reads config and shells
        // into the WireGuard container. The flag decorates the list; the list is the fleet. Blanking the
        // whole fleet view because one machine could not be labelled is much the worse of the two.
        Machine other = new Machine(mid("nas"), "nas", MachineType.LAN_SERVER,
            null, null, null, null, null, null, null,
            "192.168.3.0/24", "192.168.3.50", true, 2375, net.fjordomatic.domain.DeviceCategory.NAS, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(other));
        when(getFjordServerUseCase.getFjordServerMachine())
            .thenThrow(new RuntimeException("wireguard container is restarting"));

        var response = controller.list();

        assertThat(response).hasSize(1);
        assertThat(response.get(0).fjordServer()).isFalse();
    }

    @Test
    void list_carriesTheDomainsAnswerToWhetherAMachineCouldRelayANetwork() {
        // #333: the browser used to work this out itself, with a SERVER_TYPES set that includes LAN_SERVER
        // — a machine that has no tunnel to route into. Two definitions of one rule, already disagreeing;
        // only an incidental "is it a peer?" guard kept them lined up. The domain answers it now.
        Machine relay = new Machine(mid("colina"), "Colina 27", MachineType.UBUNTU_SERVER, "pk",
            "10.13.13.3/32", null, null, null, null, null, null, null, true, null,
            DeviceCategory.SERVER, null);
        Machine printer = new Machine(mid("printer"), "printer", MachineType.LAN_SERVER, null, null, null,
            null, null, null, null, "192.168.1.0/24", "192.168.1.11", false, null,
            DeviceCategory.PRINTER, null);
        Machine phone = new Machine(mid("phone"), "phone", MachineType.MOBILE_CLIENT, "pk", "10.13.13.8/32",
            null, null, null, null, null, null, null, false, null, DeviceCategory.LAPTOP, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(relay, printer, phone));

        var response = controller.list();

        assertThat(response).extracting("name", "canRelayALan")
            .containsExactly(tuple("Colina 27", true), tuple("printer", false), tuple("phone", false));
    }

    @Test
    void list_reportsHasCredentialPerMachine() {
        // The Explorer tree gates a machine's Files/Disk entries on Fjord actually holding an SSH
        // credential for it, not merely the ssh-access toggle — so GET /machines carries hasCredential
        // per machine: true iff a stored host credential with a secret exists for that name.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(mid("nas"), "nas", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.50", true, 2375, DeviceCategory.NAS, null),
            new Machine(mid("printer"), "printer", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.20", false, null, DeviceCategory.PRINTER, null)
        ));
        when(getHostCredentialUseCase.getHostCredential(mid("nas"))).thenReturn(
            Optional.of(new HostCredentialView(mid("nas"), "root", AuthMethod.PASSWORD, true, false)));

        var response = controller.list();

        assertThat(response).extracting("name", "hasCredential")
            .containsExactly(tuple("nas", true), tuple("printer", false));
    }

    @Test
    void list_reportsTheEffectiveUserFjordActsAsPerMachine() {
        // #346. The credential's username IS the user Fjord acts as, and it is already stored — so this
        // costs no new SSH round trip. The DietPi box logs in as root (delete there can remove something
        // the machine needs to boot); the Ubuntu one logs in as an ordinary account. Same tree, same
        // buttons, two completely different blast radii, and until now nothing said which was which.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(mid("dietpi"), "dietpi", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.60", false, null, DeviceCategory.SERVER, null),
            new Machine(mid("ubuntu"), "ubuntu", MachineType.UBUNTU_SERVER,
                null, null, null, null, null, null, null,
                null, null, false, null, DeviceCategory.SERVER, null)
        ));
        when(getHostCredentialUseCase.getHostCredential(mid("dietpi"))).thenReturn(
            Optional.of(new HostCredentialView(mid("dietpi"), "root", AuthMethod.PASSWORD, true, false)));
        when(getHostCredentialUseCase.getHostCredential(mid("ubuntu"))).thenReturn(
            Optional.of(new HostCredentialView(mid("ubuntu"), "geir", AuthMethod.PRIVATE_KEY, true, false)));

        var response = controller.list();

        assertThat(response).extracting("effectiveUsername", "effectiveUserPrivileged")
            .containsExactly(tuple("root", true), tuple("geir", false));
    }

    @Test
    void list_effectiveUserIsNullWhenNoCredentialIsStored() {
        // Nothing to act as: Fjord holds no login here, so it must not invent an unprivileged-looking one.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(mid("printer"), "printer", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.20", false, null, DeviceCategory.PRINTER, null)
        ));

        assertThat(controller.list().get(0).effectiveUsername()).isNull();
        assertThat(controller.list().get(0).effectiveUserPrivileged()).isFalse();
    }

    @Test
    void list_readsEachMachinesCredentialExactlyOnce() {
        // hasCredential and the effective user come out of the same lookup — the credential vault is read
        // from disk, and reading it twice per machine per fleet load is a cost with nothing to buy it.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(mid("nas"), "nas", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.50", true, 2375, DeviceCategory.NAS, null)
        ));
        when(getHostCredentialUseCase.getHostCredential(mid("nas"))).thenReturn(
            Optional.of(new HostCredentialView(mid("nas"), "root", AuthMethod.PASSWORD, true, false)));

        controller.list();

        verify(getHostCredentialUseCase, times(1)).getHostCredential(mid("nas"));
    }

    @Test
    void list_exposesEffectiveSshAccess() {
        // A server defaults SSH-on; a phone client defaults SSH-off — both with no override.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(MachineId.generate(), "alice", MachineType.UBUNTU_SERVER,
                null, null, null, null, null, null, null,
                null, null, true, null, net.fjordomatic.domain.DeviceCategory.SERVER, null),
            new Machine(MachineId.generate(), "phone", MachineType.MOBILE_CLIENT,
                null, null, null, null, null, null, null,
                null, null, false, null, net.fjordomatic.domain.DeviceCategory.PHONE, null)
        ));

        var response = controller.list();

        assertThat(response.get(0).sshAccess()).isTrue();
        assertThat(response.get(1).sshAccess()).isFalse();
    }

    @Test
    void list_reportsSshServerPresencePerMachine() {
        // Composed at the driving edge, exactly like hasCredential: GetSshServerPresenceUseCase reads what
        // RemoteDiskWatcher's existing sweep already observed, so the Explorer can grey out SSH-dependent
        // controls without waiting for a click to fail.
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(mid("kitchen"), "Roon kjøkken", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.104", false, null, DeviceCategory.SERVER, null),
            new Machine(mid("nas"), "nas", MachineType.LAN_SERVER,
                null, null, null, null, null, null, null,
                "192.168.3.0/24", "192.168.3.50", true, 2375, DeviceCategory.NAS, null)
        ));
        when(getSshServerPresenceUseCase.getSshServerPresence(mid("kitchen")))
            .thenReturn(SshServerPresence.ABSENT);
        when(getSshServerPresenceUseCase.getSshServerPresence(mid("nas")))
            .thenReturn(SshServerPresence.PRESENT);

        var response = controller.list();

        assertThat(response).extracting("name", "sshServerPresence")
            .containsExactly(
                tuple("Roon kjøkken", SshServerPresence.ABSENT),
                tuple("nas", SshServerPresence.PRESENT));
    }

    // --- SSH access override (#307) ---

    @Test
    void setSshAccess_delegatesAndReturnsEffectiveState() {
        when(setMachineSshAccessUseCase.setMachineSshAccess(mid("nas"), false)).thenReturn(false);

        var response = controller.setSshAccess(mid("nas").value(), new MachineRestController.SshAccessRequest(false));

        assertThat(response.sshAccess()).isFalse();
        verify(setMachineSshAccessUseCase).setMachineSshAccess(mid("nas"), false);
    }

    @Test
    void setSshAccess_enabledTrue_delegates() {
        when(setMachineSshAccessUseCase.setMachineSshAccess(mid("nas"), true)).thenReturn(true);

        var response = controller.setSshAccess(mid("nas").value(), new MachineRestController.SshAccessRequest(true));

        assertThat(response.sshAccess()).isTrue();
    }

    // --- Fjord server singleton (#311) ---

    @Test
    void fjordServer_reportsEffectiveSshAccessAndCredentialPresence() {
        when(getFjordServerUseCase.getFjordServerMachine()).thenReturn(Machine.fjordServer(MachineId.generate(), null));
        when(getHostCredentialUseCase.getHostCredential(any(MachineId.class)))
            .thenReturn(Optional.of(new HostCredentialView(mid(LanAnchor.FJORD_SERVER_NAME), "root",
                AuthMethod.PASSWORD, true, false)));

        var response = controller.fjordServer();

        assertThat(response.name()).isEqualTo(LanAnchor.FJORD_SERVER_NAME);
        assertThat(response.sshAccess()).isTrue();  // server defaults on
        assertThat(response.hasCredential()).isTrue();
    }

    @Test
    void fjordServer_noCredentialStored_reportsHasCredentialFalse() {
        when(getFjordServerUseCase.getFjordServerMachine()).thenReturn(Machine.fjordServer(MachineId.generate(), false));
        when(getHostCredentialUseCase.getHostCredential(any(MachineId.class)))
            .thenReturn(Optional.empty());

        var response = controller.fjordServer();

        assertThat(response.sshAccess()).isFalse();
        assertThat(response.hasCredential()).isFalse();
    }

    // --- clear host key (#308) ---

    @Test
    void clearHostKey_returns204AndDelegates() {
        var response = controller.clearHostKey(mid("nas").value());

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(clearHostKeyUseCase).clearHostKey(mid("nas"));
    }

    // --- progressive-adoption nudges (edge-composed) ---
    //
    // The controller (a driving adapter) gathers each signal from an existing *UseCase and hands them to
    // the pure-domain MachineNudges assembler, which owns the decisions. No service composes across
    // domains and no service implements a driven port to expose nudges.

    @Test
    void nudges_composesFromTheGatheredSignals() {
        // A reachable, storage-class peer, with an exposed service, an SSH credential, nothing backed up,
        // and no backup server anywhere ⇒ all three nudges fire.
        String freshHandshake = String.valueOf(System.currentTimeMillis() / 1000);
        Machine alice = new Machine(mid("alice"), "alice", MachineType.UBUNTU_SERVER, "pk", "10.13.13.2/32",
            "1.2.3.4", "51820", freshHandshake, "1", "1", null, null, true, null, DeviceCategory.SERVER, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(alice));
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of(
            new PublishableService(PublishableSource.PEER, mid("alice").value(), "alice",
                "10.13.13.2", "grafana", 3000, null, false)));
        when(getHostCredentialUseCase.getHostCredential(mid("alice"))).thenReturn(
            Optional.of(new HostCredentialView(mid("alice"), "root", AuthMethod.PASSWORD, true, false)));
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of());
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of());

        var response = controller.nudges(mid("alice").value());

        assertThat(response).extracting(MachineRestController.NudgeResponse::kind)
            .containsExactly(MachineNudge.Kind.PUBLISH.name(), MachineNudge.Kind.BACK_UP.name(),
                MachineNudge.Kind.DESIGNATE_BACKUP_SERVER.name());
        assertThat(response.get(0).title()).isNotBlank();
    }

    @Test
    void nudges_doesNotCountServicesTheOperatorHasIgnored() {
        // The publishable feed carries dismissed services too — the Explorer folds them behind "Show
        // ignored" rather than dropping them, so the operator can undo. Counting them into the nudge asks
        // a question that was already answered: the Fjord server kept offering to publish mosquitto's 1883
        // and borg's 8022 long after they were dismissed.
        String freshHandshake = String.valueOf(System.currentTimeMillis() / 1000);
        Machine alice = new Machine(mid("alice"), "alice", MachineType.UBUNTU_SERVER, "pk", "10.13.13.2/32",
            "1.2.3.4", "51820", freshHandshake, "1", "1", null, null, true, null, DeviceCategory.SERVER, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(alice));
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of(
            new PublishableService(PublishableSource.PEER, mid("alice").value(), "alice",
                "10.13.13.2", "mosquitto-broker", 1883, null, true),
            new PublishableService(PublishableSource.PEER, mid("alice").value(), "alice",
                "10.13.13.2", "grafana", 3000, null, false)));
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of());
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of(
            new BackupServer("nas-borg", mid("nas"), "192.168.3.50", 8022, "borg", null, "/vol", true)));

        var response = controller.nudges(mid("alice").value());

        assertThat(response).extracting(MachineRestController.NudgeResponse::kind)
            .contains(MachineNudge.Kind.PUBLISH.name());
        assertThat(response.get(0).title()).isEqualTo("Publish 1 service");
    }

    @Test
    void nudges_everyServiceOnTheMachineIgnored_raisesNoPublishNudge() {
        String freshHandshake = String.valueOf(System.currentTimeMillis() / 1000);
        Machine alice = new Machine(mid("alice"), "alice", MachineType.UBUNTU_SERVER, "pk", "10.13.13.2/32",
            "1.2.3.4", "51820", freshHandshake, "1", "1", null, null, true, null, DeviceCategory.SERVER, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(alice));
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of(
            new PublishableService(PublishableSource.PEER, mid("alice").value(), "alice",
                "10.13.13.2", "mosquitto-broker", 1883, null, true)));
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of());
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of(
            new BackupServer("nas-borg", mid("nas"), "192.168.3.50", 8022, "borg", null, "/vol", true)));

        var response = controller.nudges(mid("alice").value());

        assertThat(response).extracting(MachineRestController.NudgeResponse::kind)
            .doesNotContain(MachineNudge.Kind.PUBLISH.name());
    }

    @Test
    void nudges_backUpAsRoot_isRaisedFromTheMachinesLastRun() {
        // #334: the signal the nudge rests on is a run, so the controller has to gather runs too. It still
        // decides nothing — it hands the job and the run to MachineNudges and renders what comes back.
        Machine colina = new Machine(mid("colina"), "colina", MachineType.UBUNTU_SERVER, "pk",
            "10.13.13.3/32", "1.2.3.4", "51820", "1", "1", "1", null, null, true, null,
            DeviceCategory.SERVER, null);
        BackupJob job = new BackupJob("colina", mid("colina"), "colina-repo", List.of("/home"), List.of(),
            7, 4, 6, "zstd,6", true, false);
        BackupRun incomplete = BackupRun.fromExitCode(job, "run-1", Instant.EPOCH, Instant.EPOCH, 1,
            "/home/mqtt/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'\n");
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(colina));
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of());
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of(job));
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of(
            new BackupServer("nas-borg", mid("nas"), "192.168.3.50", 8022, "borg", null, "/vol", true)));
        when(getBackupRunsUseCase.latestForMachine(mid("colina"))).thenReturn(Optional.of(incomplete));

        var response = controller.nudges(mid("colina").value());

        assertThat(response).extracting(MachineRestController.NudgeResponse::kind)
            .containsExactly(MachineNudge.Kind.BACK_UP_AS_ROOT.name());
        assertThat(response.get(0).evidence()).contains("/home/mqtt/mosquitto.db");
    }

    @Test
    void nudges_routeLan_offersWhatTheSweepDetected_andCarriesTheCidrToActOn() {
        // #333: the detected network is one more already-cached signal the edge gathers. The controller
        // still decides nothing — it hands the reading to MachineNudges and renders what comes back.
        Machine colina = new Machine(mid("colina"), "Colina 27", MachineType.UBUNTU_SERVER, "pk",
            "10.13.13.3/32", "1.2.3.4", "51820", "1", "1", "1", null, null, true, null,
            DeviceCategory.SERVER, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(colina));
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of());
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of());
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of(
            new BackupServer("nas-borg", mid("nas"), "192.168.3.50", 8022, "borg", null, "/vol", true)));
        when(getMachineNetworksUseCase.getMachineNetworks(mid("colina"))).thenReturn(
            MachineNetworks.parse("""
                2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0
                default via 192.168.1.1 dev eth0 proto dhcp metric 100
                """));

        var response = controller.nudges(mid("colina").value());

        assertThat(response).extracting(MachineRestController.NudgeResponse::kind)
            .containsExactly(MachineNudge.Kind.ROUTE_LAN.name());
        assertThat(response.get(0).title()).contains("192.168.1.0/24");
        assertThat(response.get(0).evidence()).contains("eth0");
        assertThat(response.get(0).value()).isEqualTo("192.168.1.0/24");
    }

    @Test
    void nudges_routeLan_readsTheFjordServersOwnNetworksToJudgeTheOffer() {
        // Accepting installs `ip route <cidr> dev wg0` on the Fjord server, so the network that must not be
        // captured is the Fjord server's — gathered at the edge from the very same use case, by identity.
        Machine fjordServer = Machine.fjordServer(mid("vaier"), null);
        Machine colina = new Machine(mid("colina"), "Colina 27", MachineType.UBUNTU_SERVER, "pk",
            "10.13.13.3/32", "1.2.3.4", "51820", "1", "1", "1", null, null, true, null,
            DeviceCategory.SERVER, null);
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(colina));
        when(getFjordServerUseCase.getFjordServerMachine()).thenReturn(fjordServer);
        when(getPublishableServicesUseCase.getPublishableServices()).thenReturn(List.of());
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of());
        when(getBackupServersUseCase.getBackupServers()).thenReturn(List.of(
            new BackupServer("nas-borg", mid("nas"), "192.168.3.50", 8022, "borg", null, "/vol", true)));
        // Colina sits in the same VPC subnet as Fjord itself — routing it would sever Fjord's own uplink.
        when(getMachineNetworksUseCase.getMachineNetworks(mid("colina"))).thenReturn(
            MachineNetworks.parse("""
                2: eth0    inet 172.31.40.9/20 scope global eth0
                default via 172.31.32.1 dev eth0
                """));
        when(getMachineNetworksUseCase.getMachineNetworks(mid("vaier"))).thenReturn(
            MachineNetworks.parse("""
                2: ens5    inet 172.31.37.204/20 scope global ens5
                default via 172.31.32.1 dev ens5
                """));

        var response = controller.nudges(mid("colina").value());

        assertThat(response).isEmpty();
    }

    @Test
    void nudges_unknownMachine_404() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of());

        assertThatThrownBy(() -> controller.nudges(mid("ghost").value()))
            .isInstanceOf(NotFoundException.class);
    }

    // --- a machine's filesystems (#323 slice C, fixed by #325) ---
    //
    // A sibling of /machines/{machine}/files: a non-whitelisted path under /machines, so it sits behind the
    // admin auth chain automatically — reading or configuring a machine's disks is never anonymous. (The
    // whitelist is an explicit Path() list on the vaier-public Traefik router in docker-compose.yml; nothing
    // under /machines is on it.)

    private static MachineFilesystemUco filesystem(String machine, String mount, int usedPercent,
                                                   int threshold, boolean watched, boolean above) {
        return new MachineFilesystemUco(machine, "/dev/sda1", mount, 1000L, 500L, 500L, "1.0 MiB",
            "500.0 KiB", usedPercent, threshold, watched, above);
    }

    @Test
    void disk_reportsEveryFilesystem_notJustRoot() {
        // The #325 fix at the REST seam: /volume1 (39%, the volume that holds every borg backup) has to come
        // back alongside / (88%, the DSM system partition), or the operator still cannot see the disk that
        // matters.
        when(getMachineDiskUsageUseCase.getDiskUsage(mid("NAS"))).thenReturn(List.of(
            filesystem("NAS", "/", 88, 95, true, false),
            filesystem("NAS", "/volume1", 39, 85, true, false)));

        var response = controller.disk(mid("NAS").value());

        assertThat(response).extracting(MachineRestController.FilesystemResponse::mountPoint)
            .containsExactly("/", "/volume1");
    }

    @Test
    void disk_reportsTheUsageTheSizeAndTheThresholdEachFilesystemIsJudgedAgainst() {
        when(getMachineDiskUsageUseCase.getDiskUsage(mid("Apalveien 5"))).thenReturn(List.of(
            new MachineFilesystemUco("Apalveien 5", "/dev/root", "/", 30298176L, 18178905L, 10566487L,
                "28.9 GiB", "10.1 GiB", 63, 80, true, false)));

        var root = controller.disk(mid("Apalveien 5").value()).get(0);

        assertThat(root.machine()).isEqualTo("Apalveien 5");
        assertThat(root.mountPoint()).isEqualTo("/");
        assertThat(root.usedPercent()).isEqualTo(63);
        assertThat(root.size()).isEqualTo("28.9 GiB");
        assertThat(root.available()).isEqualTo("10.1 GiB");
        assertThat(root.thresholdPercent()).isEqualTo(80);
        assertThat(root.watched()).isTrue();
        assertThat(root.aboveThreshold()).isFalse();
    }

    @Test
    void disk_carriesTheDomainsOwnPressureVerdict_theBrowserNeverRecomputesIt() {
        when(getMachineDiskUsageUseCase.getDiskUsage(mid("Colina 27"))).thenReturn(List.of(
            filesystem("Colina 27", "/", 91, 80, true, true)));

        assertThat(controller.disk(mid("Colina 27").value()).get(0).aboveThreshold()).isTrue();
    }

    // --- the fleet's disk standings, in one request ---

    @Test
    void diskStandings_answerForTheWholeFleetInOneRequest_fromWhatTheSweepAlreadyRead() {
        // One request, memory-backed, nothing woken. Per-machine it would be N requests for the fleet
        // listing's ambience, and the on-demand /machines/{id}/disk read behind each of them would df every
        // sleeping machine in the house on page load.
        when(getMachineDiskStandingsUseCase.getMachineDiskStandings()).thenReturn(List.of(
            standing(mid("NAS"), "/volume1", 91, 85, 1, 3),
            standing(mid("Colina 27"), "/", 62, 85, 0, 1)));

        var standings = controller.diskStandings();

        assertThat(standings).extracting(MachineRestController.DiskStandingResponse::machineId,
                MachineRestController.DiskStandingResponse::mountPoint,
                MachineRestController.DiskStandingResponse::usedPercent,
                MachineRestController.DiskStandingResponse::level)
            .containsExactly(
                tuple(mid("NAS").value(), "/volume1", 91, "BREACHING"),
                tuple(mid("Colina 27").value(), "/", 62, "CLEAR"));
    }

    @Test
    void diskStandings_carryTheDomainsOwnLevel_theBrowserNeverRecomputesIt() {
        // 84% against an 85% threshold is not breaching, and it is not "fine" either. The browser must not
        // be the thing deciding which — DiskStandingLevel is, once, for every surface.
        when(getMachineDiskStandingsUseCase.getMachineDiskStandings())
            .thenReturn(List.of(standing(mid("NAS"), "/volume1", 84, 85, 0, 2)));

        assertThat(controller.diskStandings()).singleElement().satisfies(response -> {
            assertThat(response.level()).isEqualTo("CLOSING");
            assertThat(response.thresholdPercent()).isEqualTo(85);
            assertThat(response.breachingFilesystems()).isZero();
            assertThat(response.watchedFilesystems()).isEqualTo(2);
        });
    }

    @Test
    void diskStandings_beforeTheFirstSweep_areEmpty_soNoCardDrawsAMarkItDidNotEarn() {
        when(getMachineDiskStandingsUseCase.getMachineDiskStandings()).thenReturn(List.of());

        assertThat(controller.diskStandings()).isEmpty();
    }

    private static MachineDiskStanding standing(MachineId machineId, String mountPoint, int usedPercent,
                                                int thresholdPercent, int breaching, int watched) {
        return MachineDiskStanding.builder()
            .machineId(machineId)
            .worstMountPoint(mountPoint)
            .worstUsedPercent(usedPercent)
            .worstThresholdPercent(thresholdPercent)
            .breachingFilesystems(breaching)
            .watchedFilesystems(watched)
            .build();
    }

    // --- setting one filesystem's watch (#325) ---

    @Test
    void setDiskWatch_takesTheMountPointInTheBody_becauseAMountPointContainsSlashes() {
        // /volume1/@docker/... in a path variable would be a routing nightmare and an encoding bug waiting to
        // happen. The mount point travels in the body, where a slash is just a character.
        var response = controller.setDiskWatch(mid("NAS").value(),
            new MachineRestController.DiskWatchRequest("/volume1", true, 90));

        verify(setDiskWatchUseCase).setDiskWatch(mid("NAS"), "/volume1", true, 90);
        assertThat(response.mountPoint()).isEqualTo("/volume1");
        assertThat(response.watched()).isTrue();
        assertThat(response.thresholdPercent()).isEqualTo(90);
    }

    @Test
    void setDiskWatch_canMuteAFilesystem_andCanClearItsOwnThreshold() {
        controller.setDiskWatch(mid("NAS").value(), new MachineRestController.DiskWatchRequest("/", false, null));

        verify(setDiskWatchUseCase).setDiskWatch(mid("NAS"), "/", false, null);
    }

    /**
     * There is no name-matching rule here any more. The endpoint is given an identity, so "a name the
     * uniqueness guard would call the same machine" has stopped being a question — and an id no machine
     * has is a plain 404 rather than something that might fuzzily match.
     */
    @Test
    void nudges_anIdNoMachineHas_is404() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(mid("Colina 27"), "Colina 27", MachineType.UBUNTU_SERVER, "pk",
                "10.13.13.3/32", null, null, null, null, null, null, null, true, null,
                DeviceCategory.SERVER, null)));

        assertThatThrownBy(() -> controller.nudges(mid("ghost").value()))
            .isInstanceOf(NotFoundException.class);
    }
}
