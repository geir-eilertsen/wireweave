package net.vaier.rest;

import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.FleetCredential;
import net.vaier.domain.FleetCredentialStanding;
import net.vaier.domain.FleetCredentialState;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForPersistingFleetCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FleetCredentialDistributorTest {

    private static final String CONTENT = "{\"token\":\"totally-secret-value\"}";
    private static final String NAME = "claude-oauth";

    @Mock GetMachinesUseCase machines;
    @Mock GetHostCredentialUseCase hostCredentials;
    @Mock RunRemoteCommandUseCase remoteCommand;
    @Mock ForPersistingFleetCredentials fleetCredentials;

    @InjectMocks FleetCredentialDistributor distributor;

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    private static Machine machine(String name, MachineType type, DeviceCategory category) {
        return new Machine(mid(name), name, type, null, null, null, null, null, null, null,
            null, null, false, null, category, null);
    }

    private static FleetCredential credential() {
        return FleetCredential.of(NAME, "~/.claude/.credentials.json", "0600", CONTENT);
    }

    private static String digest() throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(CONTENT.getBytes(StandardCharsets.UTF_8)));
    }

    private static CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "", false, "fp");
    }

    private static String present(String owner, String mode, String digest) {
        return FleetCredential.REPORT_MARKER + " state=present user=geir owner=" + owner
            + " mode=" + mode + " digest=" + digest;
    }

    private static String absent() {
        return FleetCredential.REPORT_MARKER + " state=absent user=geir";
    }

    private static HostCredentialView view(String name) {
        return new HostCredentialView(mid(name), "geir", AuthMethod.PRIVATE_KEY, true, false);
    }

    private FleetCredentialStanding standingFor(List<FleetCredentialStanding> standings, String name) {
        return standings.stream().filter(s -> s.machineId().equals(mid(name))).findFirst().orElseThrow();
    }

    @BeforeEach
    void setUp() {
        lenient().when(fleetCredentials.getByName(NAME)).thenReturn(Optional.of(credential()));
    }

    // ---- distribute -------------------------------------------------------------------------

    @Test
    void distribute_writesToEverySshCapableCredentialledMachine() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("geir", "600", digest())));

        List<FleetCredentialStanding> standings = distributor.distributeFleetCredential(NAME);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(remoteCommand).run(eq(mid("nas")), command.capture());
        assertThat(command.getValue()).isEqualTo(credential().writeCommand());
        assertThat(standingFor(standings, "nas").state()).isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void distribute_neverPutsTheSecretOnTheCommandLineInTheClear() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.distributeFleetCredential(NAME);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(remoteCommand).run(eq(mid("nas")), command.capture());
        assertThat(command.getValue()).doesNotContain("totally-secret-value");
    }

    @Test
    void distribute_skipsAPhone_andNeverReachesIt() {
        Machine phone = machine("phone", MachineType.MOBILE_CLIENT, DeviceCategory.PHONE);
        when(machines.getAllMachines()).thenReturn(List.of(phone));

        List<FleetCredentialStanding> standings = distributor.distributeFleetCredential(NAME);

        assertThat(standingFor(standings, "phone").state()).isEqualTo(FleetCredentialState.SKIPPED);
        verify(remoteCommand, never()).run(any(), anyString());
    }

    @Test
    void distribute_skipsAMachineVaierHoldsNoLoginFor_neverAnError() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.empty());

        List<FleetCredentialStanding> standings = distributor.distributeFleetCredential(NAME);

        assertThat(standingFor(standings, "nas").state()).isEqualTo(FleetCredentialState.SKIPPED);
        verify(remoteCommand, never()).run(any(), anyString());
    }

    @Test
    void distribute_failsWhenTheFileLandedOwnedBySomebodyElse() throws Exception {
        // The silent case: uid-1000 Vaier writing a root-owned 0600 file. It exists, it looks right,
        // and it cannot be read by whoever needs it.
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("root", "600", digest())));

        List<FleetCredentialStanding> standings = distributor.distributeFleetCredential(NAME);

        assertThat(standingFor(standings, "nas").state()).isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void distribute_failsWhenTheModeIsNotWhatWasAskedFor() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("geir", "644", digest())));

        assertThat(standingFor(distributor.distributeFleetCredential(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void distribute_reportsAnUnreachableMachineAsUnreachable_notAsAFailure() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenThrow(new SshConnectException("no route"));

        assertThat(standingFor(distributor.distributeFleetCredential(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.UNREACHABLE);
    }

    @Test
    void distribute_oneBadMachineNeverStopsTheRest() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        Machine nuc = machine("nuc", MachineType.UBUNTU_SERVER, DeviceCategory.SERVER);
        when(machines.getAllMachines()).thenReturn(List.of(nas, nuc));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(hostCredentials.getHostCredential(mid("nuc"))).thenReturn(Optional.of(view("nuc")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenThrow(new SshConnectException("down"));
        when(remoteCommand.run(eq(mid("nuc")), anyString()))
            .thenReturn(ok(present("geir", "600", digest())));

        List<FleetCredentialStanding> standings = distributor.distributeFleetCredential(NAME);

        assertThat(standingFor(standings, "nas").state()).isEqualTo(FleetCredentialState.UNREACHABLE);
        assertThat(standingFor(standings, "nuc").state()).isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void distribute_standsTheCredentialUpOnlyOnceItHasActuallyLandedSomewhere() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.distributeFleetCredential(NAME);

        ArgumentCaptor<FleetCredential> saved = ArgumentCaptor.forClass(FleetCredential.class);
        verify(fleetCredentials).save(saved.capture());
        assertThat(saved.getValue().distributed()).isTrue();
    }

    @Test
    void distribute_thatReachedNobodyDoesNotLicenseTheBackgroundSweep() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenThrow(new SshConnectException("down"));

        distributor.distributeFleetCredential(NAME);

        verify(fleetCredentials, never()).save(any());
    }

    @Test
    void distribute_ofAnUnknownCredentialIsANotFound() {
        when(fleetCredentials.getByName("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> distributor.distributeFleetCredential("nope"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---- withdraw ---------------------------------------------------------------------------

    @Test
    void withdraw_removesTheFileFromEveryMachineItCouldHaveReached() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString())).thenReturn(ok(absent()));

        List<FleetCredentialStanding> standings = distributor.withdrawFleetCredential(NAME);

        ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
        verify(remoteCommand).run(eq(mid("nas")), command.capture());
        assertThat(command.getValue()).isEqualTo(credential().removeCommand());
        assertThat(standingFor(standings, "nas").state()).isEqualTo(FleetCredentialState.WITHDRAWN);
    }

    @Test
    void withdraw_failsWhenTheFileIsStillThere() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("geir", "600", digest())));

        assertThat(standingFor(distributor.withdrawFleetCredential(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.FAILED);
    }

    @Test
    void withdraw_standsTheCredentialDownSoTheSweepStopsHealingItBack() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(fleetCredentials.getByName(NAME)).thenReturn(Optional.of(credential().markDistributed()));
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString())).thenReturn(ok(absent()));

        distributor.withdrawFleetCredential(NAME);

        ArgumentCaptor<FleetCredential> saved = ArgumentCaptor.forClass(FleetCredential.class);
        verify(fleetCredentials).save(saved.capture());
        assertThat(saved.getValue().distributed()).isFalse();
    }

    @Test
    void withdraw_ofAnUnknownCredentialIsANotFound() {
        when(fleetCredentials.getByName("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> distributor.withdrawFleetCredential("nope"))
            .isInstanceOf(NotFoundException.class);
    }

    // ---- standings --------------------------------------------------------------------------

    @Test
    void standings_areEmptyUntilVaierHasLooked() {
        assertThat(distributor.getFleetCredentialStandings(NAME)).isEmpty();
    }

    @Test
    void standings_reportWhatTheLastDistributionSaw() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.distributeFleetCredential(NAME);

        assertThat(standingFor(distributor.getFleetCredentialStandings(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.CURRENT);
    }

    // ---- the Stage 2 reconcile ---------------------------------------------------------------

    @Test
    void reconcile_neverPushesACredentialTheOperatorHasNotDistributed() {
        when(fleetCredentials.getAll()).thenReturn(List.of(credential()));

        distributor.reconcileFleetCredentials();

        verify(machines, never()).getAllMachines();
        verify(remoteCommand, never()).run(any(), anyString());
    }

    @Test
    void reconcile_healsAMachineThatIsMissingTheCredential() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        FleetCredential distributed = credential().markDistributed();
        when(fleetCredentials.getAll()).thenReturn(List.of(distributed));
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.verifyCommand())))
            .thenReturn(ok(absent()));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.writeCommand())))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.reconcileFleetCredentials();

        verify(remoteCommand).run(mid("nas"), distributed.writeCommand());
        assertThat(standingFor(distributor.getFleetCredentialStandings(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void reconcile_healsAMachineWhoseCopyHasDrifted() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        FleetCredential distributed = credential().markDistributed();
        when(fleetCredentials.getAll()).thenReturn(List.of(distributed));
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.verifyCommand())))
            .thenReturn(ok(present("geir", "600", "deadbeef")));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.writeCommand())))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.reconcileFleetCredentials();

        verify(remoteCommand).run(mid("nas"), distributed.writeCommand());
    }

    @Test
    void reconcile_leavesACurrentMachineAlone() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        FleetCredential distributed = credential().markDistributed();
        when(fleetCredentials.getAll()).thenReturn(List.of(distributed));
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.verifyCommand())))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.reconcileFleetCredentials();

        verify(remoteCommand, never()).run(mid("nas"), distributed.writeCommand());
        assertThat(standingFor(distributor.getFleetCredentialStandings(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.CURRENT);
    }

    @Test
    void reconcile_isQuietAboutAnUnreachableMachine() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        FleetCredential distributed = credential().markDistributed();
        when(fleetCredentials.getAll()).thenReturn(List.of(distributed));
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), anyString()))
            .thenThrow(new SshConnectException("asleep"));

        assertThatCode(() -> distributor.reconcileFleetCredentials()).doesNotThrowAnyException();
        assertThat(standingFor(distributor.getFleetCredentialStandings(NAME), "nas").state())
            .isEqualTo(FleetCredentialState.UNREACHABLE);
    }

    @Test
    void reconcile_neverStandsACredentialUpOnItsOwn() throws Exception {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS);
        FleetCredential distributed = credential().markDistributed();
        when(fleetCredentials.getAll()).thenReturn(List.of(distributed));
        when(machines.getAllMachines()).thenReturn(List.of(nas));
        when(hostCredentials.getHostCredential(mid("nas"))).thenReturn(Optional.of(view("nas")));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.verifyCommand())))
            .thenReturn(ok(absent()));
        when(remoteCommand.run(eq(mid("nas")), eq(distributed.writeCommand())))
            .thenReturn(ok(present("geir", "600", digest())));

        distributor.reconcileFleetCredentials();

        verify(fleetCredentials, never()).save(any());
    }

    @Test
    void reconcile_oneBrokenCredentialNeverStallsTheSweep() {
        FleetCredential distributed = credential().markDistributed();
        when(fleetCredentials.getAll()).thenReturn(List.of(distributed));
        when(machines.getAllMachines()).thenThrow(new IllegalStateException("registry down"));

        assertThatCode(() -> distributor.reconcileFleetCredentials()).doesNotThrowAnyException();
    }
}
