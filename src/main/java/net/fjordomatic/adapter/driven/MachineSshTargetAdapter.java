package net.fjordomatic.adapter.driven;

import lombok.RequiredArgsConstructor;
import net.fjordomatic.domain.HostCredential;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.NoHostCredentialException;
import net.fjordomatic.domain.SshAddress;
import net.fjordomatic.domain.SshTarget;
import net.fjordomatic.domain.port.ForGettingPeerConfigurations;
import net.fjordomatic.domain.port.ForPersistingHostCredentials;
import net.fjordomatic.domain.port.ForPersistingLanServers;
import net.fjordomatic.domain.port.ForResolvingSshTargets;
import net.fjordomatic.domain.port.ForResolvingFjordServerIdentity;
import net.fjordomatic.domain.port.ForResolvingFjordServerSshAddress;
import net.fjordomatic.domain.port.ForTrackingHostKeys;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link SshTarget} for a machine — the one copy of the resolve-address + load-vault-
 * credential + read-pinned-fingerprint logic behind every SSH consumer (the web terminal, the
 * Explorer). Keeping it in one adapter keeps the host-key trust-on-first-use path single: a second copy
 * of it would be a second place for trust to be decided.
 *
 * <p>It composes the stores rather than talking to a system of its own — the machine registries (peer
 * configs, LAN servers, the Fjord host's own address), the credential vault, and the host-key pin store —
 * and holds no rules: {@link SshAddress} decides where a machine answers, and the vault's absence of a
 * credential is the domain's {@link NoHostCredentialException}. The pinned fingerprint it returns is
 * {@code null} for a machine that has never been connected to; the caller pins on first use.
 */
@Component
@RequiredArgsConstructor
public class MachineSshTargetAdapter implements ForResolvingSshTargets {

    private final ForGettingPeerConfigurations forGettingPeerConfigurations;
    private final ForPersistingLanServers forPersistingLanServers;
    private final ForResolvingFjordServerSshAddress forResolvingFjordServerSshAddress;
    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForResolvingFjordServerIdentity forResolvingFjordServerIdentity;

    @Override
    public SshTarget resolve(MachineId machineId) {
        // Where a machine answers is a domain decision by machine kind — the adapter only hands the domain
        // the stores it needs to make it, plus the Fjord server's own id, which is the one identity that
        // lives in the Fjord config rather than in a machine store.
        String host = SshAddress.of(machineId, forGettingPeerConfigurations, forPersistingLanServers,
            forResolvingFjordServerSshAddress, fjordServerId());
        HostCredential credential = forPersistingHostCredentials.getByMachine(machineId)
            .orElseThrow(() -> new NoHostCredentialException(String.valueOf(machineId)));
        String pinned = forTrackingHostKeys.getFingerprint(machineId).orElse(null);
        return SshTarget.on(host, credential, pinned);
    }

    /**
     * The Fjord server's identity. Asked for through the port rather than read out of the config here,
     * because a Fjord that had never been assigned one used to resolve to nothing — and "no identity" then
     * matched no machine, so Fjord could not reach itself over SSH until some other caller happened to
     * assign it first.
     */
    private MachineId fjordServerId() {
        return forResolvingFjordServerIdentity.identity();
    }
}
