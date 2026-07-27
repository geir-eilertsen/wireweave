package net.vaier.adapter.driven;

import lombok.RequiredArgsConstructor;
import net.vaier.domain.HostCredential;
import net.vaier.domain.MachineId;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.SshAddress;
import net.vaier.domain.SshTarget;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForGettingPeerConfigurations;
import net.vaier.domain.port.ForPersistingHostCredentials;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingLanServers;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForResolvingVaierServerSshAddress;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link SshTarget} for a machine — the one copy of the resolve-address + load-vault-
 * credential + read-pinned-fingerprint logic behind every SSH consumer (the web terminal, the
 * Explorer). Keeping it in one adapter keeps the host-key trust-on-first-use path single: a second copy
 * of it would be a second place for trust to be decided.
 *
 * <p>It composes the stores rather than talking to a system of its own — the machine registries (peer
 * configs, LAN servers, the Vaier host's own address), the credential vault, and the host-key pin store —
 * and holds no rules: {@link SshAddress} decides where a machine answers, and the vault's absence of a
 * credential is the domain's {@link NoHostCredentialException}. The pinned fingerprint it returns is
 * {@code null} for a machine that has never been connected to; the caller pins on first use.
 */
@Component
@RequiredArgsConstructor
public class MachineSshTargetAdapter implements ForResolvingSshTargets {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForResolvingVaierServerSshAddress forResolvingVaierServerSshAddress;
    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForPersistingAppConfiguration forPersistingAppConfiguration;

    @Override
    public SshTarget resolve(MachineId machineId) {
        // Where a machine answers is a domain decision by machine kind — the adapter only hands the domain
        // the stores it needs to make it, plus the Vaier server's own id, which is the one identity that
        // lives in the Vaier config rather than in a machine store.
        String host = SshAddress.of(machineId, forGettingPeerConfigurations, forPersistingLanServers,
            forResolvingVaierServerSshAddress, vaierServerId());
        HostCredential credential = forPersistingHostCredentials.getByMachine(machineId)
            .orElseThrow(() -> new NoHostCredentialException(String.valueOf(machineId)));
        String pinned = forTrackingHostKeys.getFingerprint(machineId).orElse(null);
        return SshTarget.on(host, credential, pinned);
    }

    /** The Vaier server's stored identity, or null when it has not been assigned one — read, never minted. */
    private MachineId vaierServerId() {
        return forPersistingAppConfiguration.load()
            .flatMap(VaierConfig::vaierServerIdentity)
            .orElse(null);
    }
}
