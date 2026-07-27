package net.vaier.adapter.driven;

import net.vaier.domain.AuthMethod;
import net.vaier.domain.HostCredential;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.LanServer;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshTarget;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The one place a machine's identity becomes a connectable {@link SshTarget}: address by machine kind,
 * credential from the vault, previously pinned host key. Every SSH consumer (terminal, Explorer) goes
 * through it, so there is exactly one copy of the trust-on-first-use lookup.
 *
 * <p>Every lookup here is by {@link MachineId}. The fixtures therefore build peers and LAN servers with
 * their <em>full</em> constructors: the convenience ones mint a fresh id, which is right for a machine
 * being created and useless for a test that has to say "this stored machine is the one being asked for".
 */
@ExtendWith(MockitoExtension.class)
class MachineSshTargetAdapterTest {

    private static MachineId mid(String name) {
        return TestMachineIds.of(name);
    }

    @Mock ForGettingPeerConfigurations forGettingPeerConfigurations;
    @Mock ForPersistingLanServers forPersistingLanServers;
    @Mock ForResolvingVaierServerSshAddress forResolvingVaierServerSshAddress;
    @Mock ForPersistingHostCredentials forPersistingHostCredentials;
    @Mock ForTrackingHostKeys forTrackingHostKeys;
    @Mock ForPersistingAppConfiguration forPersistingAppConfiguration;

    @InjectMocks MachineSshTargetAdapter adapter;

    private HostCredential passwordCred(String machine) {
        return new HostCredential(mid(machine), "root", AuthMethod.PASSWORD, "pw", null, false);
    }

    /** A stored peer carrying the id it was stored with, rather than a freshly minted one. */
    private PeerConfiguration peer(String name, String tunnelIp) {
        return new PeerConfiguration(name, name, tunnelIp, "", MachineType.UBUNTU_SERVER,
            null, null, null, null, null, mid(name));
    }

    /** A stored LAN server carrying the id it was stored with. */
    private LanServer lanServer(String name, String lanAddress) {
        return new LanServer(name, lanAddress, true, 2375, null, null, null, mid(name));
    }

    /** Vaier knows its own identity — the one id that lives in the config, not in a machine store. */
    private void vaierServerIdentifiesItselfAs(MachineId machineId) {
        when(forPersistingAppConfiguration.load()).thenReturn(Optional.of(
            VaierConfig.builder().vaierServerMachineId(machineId.value()).build()));
    }

    /** Vaier has not been assigned an identity yet, so it can never be mistaken for the machine asked for. */
    private void vaierServerHasNoIdentityYet() {
        lenient().when(forPersistingAppConfiguration.load()).thenReturn(Optional.empty());
    }

    @Test
    void peer_resolvesToTheTunnelIp_withVaultCredentialAndPinnedKey() {
        vaierServerHasNoIdentityYet();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(peer("nuc", "10.13.13.9")));
        when(forPersistingHostCredentials.getByMachine(mid("nuc"))).thenReturn(Optional.of(passwordCred("nuc")));
        when(forTrackingHostKeys.getFingerprint(mid("nuc"))).thenReturn(Optional.of("SHA256:pinned"));

        SshTarget target = adapter.resolve(mid("nuc"));

        assertThat(target.host()).isEqualTo("10.13.13.9");
        assertThat(target.port()).isEqualTo(SshTarget.DEFAULT_PORT);
        assertThat(target.username()).isEqualTo("root");
        assertThat(target.authMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(target.pinnedFingerprint()).isEqualTo("SHA256:pinned");
    }

    @Test
    void lanServer_resolvesToTheLanAddress() {
        vaierServerHasNoIdentityYet();
        lenient().when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of(lanServer("nas", "192.168.3.50")));
        when(forPersistingHostCredentials.getByMachine(mid("nas"))).thenReturn(Optional.of(passwordCred("nas")));
        when(forTrackingHostKeys.getFingerprint(mid("nas"))).thenReturn(Optional.empty());

        SshTarget target = adapter.resolve(mid("nas"));

        assertThat(target.host()).isEqualTo("192.168.3.50");
        assertThat(target.pinnedFingerprint()).isNull();   // never pinned yet — first use will pin it
    }

    @Test
    void vaierServer_resolvesToTheResolvedHostAddress() {
        MachineId vaierServer = mid(LanAnchor.VAIER_SERVER_NAME);
        vaierServerIdentifiesItselfAs(vaierServer);
        when(forResolvingVaierServerSshAddress.resolve()).thenReturn("172.17.0.1");
        when(forPersistingHostCredentials.getByMachine(vaierServer))
            .thenReturn(Optional.of(passwordCred(LanAnchor.VAIER_SERVER_NAME)));
        when(forTrackingHostKeys.getFingerprint(vaierServer)).thenReturn(Optional.empty());

        SshTarget target = adapter.resolve(vaierServer);

        assertThat(target.host()).isEqualTo("172.17.0.1");
    }

    /**
     * Recognising itself is by id, not by the name {@code LanAnchor.VAIER_SERVER_NAME}. A Vaier that has
     * not been assigned an identity must not answer for a machine that merely could not be found — the
     * failure to find is the answer, and reporting Vaier's own address instead would send a command
     * intended for a fleet machine to the machine issuing it.
     */
    @Test
    void vaierServerWithoutAnIdentity_doesNotAnswerForAMachineThatCannotBeFound() {
        vaierServerHasNoIdentityYet();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> adapter.resolve(mid(LanAnchor.VAIER_SERVER_NAME)))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void unknownMachine_throwsNotFound() {
        vaierServerHasNoIdentityYet();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of());
        when(forPersistingLanServers.getAll()).thenReturn(List.of());

        assertThatThrownBy(() -> adapter.resolve(mid("ghost")))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(mid("ghost").value());
    }

    @Test
    void machineWithoutAVaultCredential_throwsNoHostCredential() {
        vaierServerHasNoIdentityYet();
        when(forGettingPeerConfigurations.getAllPeerConfigs()).thenReturn(List.of(peer("nuc", "10.13.13.9")));
        when(forPersistingHostCredentials.getByMachine(mid("nuc"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.resolve(mid("nuc")))
            .isInstanceOf(NoHostCredentialException.class);
    }
}
