package net.fjordomatic.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.application.ClearHostKeyUseCase;
import net.fjordomatic.application.DeleteHostCredentialUseCase;
import net.fjordomatic.application.EndTerminalSessionUseCase;
import net.fjordomatic.application.GenerateManagedKeypairUseCase;
import net.fjordomatic.application.GetHostCredentialUseCase;
import net.fjordomatic.application.GetHostPublicKeyUseCase;
import net.fjordomatic.application.GetSshServerPresenceUseCase;
import net.fjordomatic.application.OpenTerminalSessionUseCase;
import net.fjordomatic.application.OpenTerminalSessionUseCase.OpenedTerminal;
import net.fjordomatic.application.RunRemoteCommandUseCase;
import net.fjordomatic.application.SaveHostCredentialUseCase;
import net.fjordomatic.application.SendHostPasswordUseCase;
import net.fjordomatic.application.VerifySshCredentialUseCase;
import net.fjordomatic.domain.AuthMethod;
import net.fjordomatic.domain.CommandResult;
import net.fjordomatic.domain.HostCredential;
import net.fjordomatic.domain.HostCredentialView;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.PasswordPrompt;
import net.fjordomatic.domain.PersistentShell;
import net.fjordomatic.domain.SshCredentialDraft;
import net.fjordomatic.domain.SshCredentialVerification;
import net.fjordomatic.domain.SshServerPresence;
import net.fjordomatic.domain.SshTarget;
import net.fjordomatic.domain.port.ForCheckingSshServerPresence;
import net.fjordomatic.domain.port.ForGeneratingSshKeypairs;
import net.fjordomatic.domain.port.ForOpeningSshSessions;
import net.fjordomatic.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.fjordomatic.domain.port.ForOpeningSshSessions.SshSession;
import net.fjordomatic.domain.port.ForPersistingHostCredentials;
import net.fjordomatic.domain.port.ForResolvingSshTargets;
import net.fjordomatic.domain.port.ForRunningSshCommands;
import net.fjordomatic.domain.port.ForTrackingHostKeys;
import net.fjordomatic.domain.port.ForVerifyingSshCredentials;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The remote-shell / credential-vault domain service. It stores, reads (redacted) and deletes host
 * credentials (slice 1), and opens live SSH terminal sessions (slice 2): resolving a machine's SSH
 * address (peer tunnel IP / LAN address / Fjord host), authenticating from the vault, and pinning the
 * host key on first use. Reads go through the domain's {@link HostCredential#toView() redaction} so
 * raw secrets never leave the process.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TerminalService implements
    SaveHostCredentialUseCase,
    GetHostCredentialUseCase,
    DeleteHostCredentialUseCase,
    OpenTerminalSessionUseCase,
    EndTerminalSessionUseCase,
    RunRemoteCommandUseCase,
    SendHostPasswordUseCase,
    VerifySshCredentialUseCase,
    ClearHostKeyUseCase,
    GetSshServerPresenceUseCase,
    GenerateManagedKeypairUseCase,
    GetHostPublicKeyUseCase {

    private final ForPersistingHostCredentials forPersistingHostCredentials;
    private final ForResolvingSshTargets forResolvingSshTargets;
    private final ForOpeningSshSessions forOpeningSshSessions;
    private final ForRunningSshCommands forRunningSshCommands;
    private final ForTrackingHostKeys forTrackingHostKeys;
    private final ForVerifyingSshCredentials forVerifyingSshCredentials;
    private final ForCheckingSshServerPresence forCheckingSshServerPresence;
    private final ForGeneratingSshKeypairs forGeneratingSshKeypairs;

    @Override
    public void saveHostCredential(MachineId machineId, SshCredentialDraft draft) {
        // The draft knows how to become a vault credential; the machine's identity is what it needs, and
        // the caller already holds it — so a credential can no longer be filed against the wrong machine
        // by a name that was edited between the operator typing it and the save landing.
        forPersistingHostCredentials.save(draft.forMachine(machineId));
    }

    @Override
    public SshCredentialVerification verify(String address, int port, SshCredentialDraft credential) {
        // Orchestration only: the draft builds the pre-registration target (no pin), and the domain
        // maps the driven-port probe into the result. Nothing is persisted and nothing is pinned.
        return SshCredentialVerification.probe(credential.targetAt(address, port), forVerifyingSshCredentials);
    }

    @Override
    public Optional<HostCredentialView> getHostCredential(MachineId machineId) {
        return forPersistingHostCredentials.getByMachine(machineId).map(HostCredential::toView);
    }

    @Override
    public String generateManagedKeypair(MachineId machineId, String username) {
        // Orchestration only: the domain mints the credential — it decides key auth, no passphrase and
        // managed; which algorithm that is belongs to the port's adapter — and derives the public half.
        // This stores it and hands the public key back.
        HostCredential credential = HostCredential.generatedFor(machineId, username, forGeneratingSshKeypairs);
        forPersistingHostCredentials.save(credential);
        log.info("Generated a managed keypair for machine {} (user {})", machineId, username);
        return credential.publicKey(forGeneratingSshKeypairs);
    }

    @Override
    public Optional<String> getHostPublicKey(MachineId machineId) {
        return forPersistingHostCredentials.getByMachine(machineId)
            .map(credential -> {
                try {
                    return credential.publicKey(forGeneratingSshKeypairs);
                } catch (RuntimeException e) {
                    // A stored key Fjord cannot read has nothing to show, and this read exists only to
                    // populate a panel. Failing the whole credential dialog over it would hide the very
                    // controls the operator needs to replace that key.
                    log.warn("Could not derive the public key for machine {}: {}", machineId, e.toString());
                    return null;
                }
            });
    }

    @Override
    public void deleteHostCredential(MachineId machineId) {
        forPersistingHostCredentials.deleteByMachine(machineId);
    }

    @Override
    public OpenedTerminal openTerminal(MachineId machineId, String paneId, SshOutputListener onOutput) {
        SshTarget target = forResolvingSshTargets.resolve(machineId);
        // Probe first (a normal exec run, the same host-key trust as any command): is tmux installed on
        // this machine, and does the pane's session already exist? The domain reads it into a truthful
        // continuity, so the reconnect banner can say "reattached" only when it really was. This first
        // connection is also where an unpinned host is pinned on first use.
        CommandResult probe = forRunningSshCommands.run(target, PersistentShell.probeCommand(paneId));
        pinOnFirstUse(target, probe.hostKeyFingerprint());
        PersistentShell.Continuity continuity = PersistentShell.readProbe(probe.stdout());

        // Open the persistent shell: tmux attach-or-create for the pane, falling back to a plain login
        // shell when tmux is absent. The adapter enforces host-key trust and throws HostKeyMismatchException
        // on a changed key; other failures surface as SshAuth/SshConnect.
        SshSession session = forOpeningSshSessions.open(
            target, PersistentShell.attachOrCreateCommand(paneId), onOutput);

        // Logged by identity and address, never by name. A name here bought nothing a log reader needs —
        // the address says which machine — and buying it meant this service resolving one, which is the
        // last thing keeping a name->id registry alive.
        log.info("Opened {} terminal session to {} ({}) for pane {}",
            continuity, machineId, target.host(), PersistentShell.sessionName(paneId));
        return new OpenedTerminal(session, continuity);
    }

    @Override
    public void endTerminal(MachineId machineId, String paneId) {
        // Best-effort: the operator has already closed the pane. A host that is down, has no credential, or
        // whose key no longer matches is not something they can act on from here — and leaving the session
        // behind on an unreachable host is no worse than the state we were already in. Log and move on.
        try {
            forRunningSshCommands.run(forResolvingSshTargets.resolve(machineId),
                PersistentShell.endCommand(paneId));
            log.info("Ended terminal session {} on {}", PersistentShell.sessionName(paneId), machineId);
        } catch (RuntimeException e) {
            log.warn("Could not end terminal session {} on {}: {}",
                PersistentShell.sessionName(paneId), machineId, e.toString());
        }
    }

    @Override
    public CommandResult run(MachineId machineId, String command) {
        SshTarget target = forResolvingSshTargets.resolve(machineId);
        // Same host-key trust as the shell path: a changed key throws HostKeyMismatchException.
        CommandResult result = forRunningSshCommands.run(target, command);

        pinOnFirstUse(target, result.hostKeyFingerprint());
        return result;
    }

    @Override
    public SendPasswordResult sendPassword(MachineId machineId, SshSession session, String recentOutput) {
        try {
            Optional<HostCredential> credential = forPersistingHostCredentials.getByMachine(machineId);
            if (credential.isEmpty() || credential.get().authMethod() != AuthMethod.PASSWORD) {
                return SendPasswordResult.NO_PASSWORD_CREDENTIAL;
            }
            if (!PasswordPrompt.isAwaitingPassword(recentOutput)) {
                return SendPasswordResult.NOT_AT_PROMPT;
            }
            // The secret stays in-process: written straight into the SSH PTY, never returned or logged.
            session.write((credential.get().secret() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return SendPasswordResult.SENT;
        } catch (RuntimeException e) {
            // Never surface the secret — log only the machine and the failure class.
            log.warn("Failed to send stored password to {}: {}", machineId, e.getClass().getSimpleName());
            return SendPasswordResult.FAILED;
        }
    }

    @Override
    public void clearHostKey(MachineId machineId) {
        forTrackingHostKeys.clear(machineId);
        log.info("Cleared pinned host key for {}", machineId);
    }

    @Override
    public SshServerPresence getSshServerPresence(MachineId machineId) {
        return forCheckingSshServerPresence.getPresence(machineId);
    }

    /**
     * Trust-on-first-use: if the target had nothing pinned and the connect presented a fingerprint,
     * record it so later connects can enforce it. Shared by the shell and exec paths.
     *
     * <p>The rule itself lives on {@link SshTarget#pinOnFirstUse} — every path that reaches a machine over
     * SSH (shell, exec, SFTP listing, disk reading) pins the same way, from one copy.
     */
    private void pinOnFirstUse(SshTarget target, String presentedFingerprint) {
        target.pinOnFirstUse(presentedFingerprint, forTrackingHostKeys);
    }
}
