package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.ClearHostKeyUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupServersUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetMachineDiskUsageUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetPublishableServicesUseCase;
import net.vaier.application.GetSshServerPresenceUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.SetDiskWatchUseCase;
import net.vaier.application.SetMachineSshAccessUseCase;
import net.vaier.domain.BackupFleet;
import net.vaier.domain.EffectiveUser;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNudge;
import net.vaier.domain.MachineNudges;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.Reachability;
import net.vaier.domain.SshServerPresence;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/machines")
@RequiredArgsConstructor
@Slf4j
public class MachineRestController {

    private final GetMachinesUseCase getMachinesUseCase;
    private final GetVaierServerUseCase getVaierServerUseCase;
    private final SetMachineSshAccessUseCase setMachineSshAccessUseCase;
    private final GetHostCredentialUseCase getHostCredentialUseCase;
    private final ClearHostKeyUseCase clearHostKeyUseCase;
    private final GetMachineDiskUsageUseCase getMachineDiskUsageUseCase;
    private final SetDiskWatchUseCase setDiskWatchUseCase;
    private final GetPublishableServicesUseCase getPublishableServicesUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupServersUseCase getBackupServersUseCase;
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    private final GetSshServerPresenceUseCase getSshServerPresenceUseCase;

    /**
     * Every machine Vaier knows. Each carries {@code hasCredential} — whether Vaier actually holds an SSH
     * login for it — composed here at the driving edge from {@link GetHostCredentialUseCase}, exactly as the
     * Vaier-server and nudges endpoints do. The Explorer gates a machine's Files and Disk entries on it:
     * those ride on a credential, so the SSH-access toggle alone would grow entries that open onto a "no
     * login" wall.
     *
     * <p>Each also carries {@code sshServerPresence} — Vaier's last-known belief about whether an SSH server
     * is actually listening there, read from {@link GetSshServerPresenceUseCase}. This is the page-load half
     * of the live signal: the {@code ssh-server-presence-changed} event on the {@code vpn-peers} SSE stream
     * carries deltas after the page is open, but a fresh load has no deltas to have received, so the list
     * itself must carry the current state.
     *
     * <p>Each also carries {@code effectiveUsername} and {@code effectiveUserPrivileged} (#346) — the user
     * Vaier acts as there, and whether that user is privileged; the username is null where no credential is
     * stored. It costs no new SSH round trip: the credential's username <em>is</em> the effective user, so
     * the very lookup that answers {@code hasCredential} answers this too, and {@link EffectiveUser} owns
     * the privilege decision so nothing downstream re-derives it from the string "root".
     */
    @GetMapping
    public List<MachineResponse> list() {
        // Which machine Vaier is running on, as an identity. The browser used to work this out by
        // comparing a display name to the literal "Vaier server", which is a name doing an identity's
        // job: rename the host and Vaier stops recognising itself; let another machine take the name
        // and it mistakes that one for itself.
        //
        // Guarded, because identity-keying turns what used to be a string comparison into a lookup, and
        // a lookup can fail — resolving the Vaier server reads config and shells into the WireGuard
        // container. This flag decorates the list; the list itself is the fleet. Losing the whole fleet
        // view because one machine could not be labelled is much the worse failure of the two.
        MachineId vaierServer = resolveVaierServerId();
        return getMachinesUseCase.getAllMachines().stream()
            .map(m -> {
                // One credential lookup per machine, answering both questions it can answer: whether a
                // login is held at all, and which user that login makes Vaier on this machine. Asking
                // twice would re-read the vault off disk for an answer already in hand.
                Optional<HostCredentialView> credential = getHostCredentialUseCase.getHostCredential(m.id());
                return MachineResponse.from(m,
                    hasStoredCredential(credential),
                    m.id().equals(vaierServer),
                    getSshServerPresenceUseCase.getSshServerPresence(m.id()),
                    credential.map(HostCredentialView::username).map(EffectiveUser::of).orElse(null));
            })
            .toList();
    }

    /** The Vaier server's identity, or null when it cannot be resolved right now. Never throws. */
    private MachineId resolveVaierServerId() {
        try {
            Machine server = getVaierServerUseCase.getVaierServerMachine();
            return server == null ? null : server.id();
        } catch (RuntimeException e) {
            log.warn("Could not resolve the Vaier server's identity for the machine list: {}", e.getMessage());
            return null;
        }
    }

    /** Whether a host SSH credential with a secret is stored for this machine. */
    private boolean hasStoredCredential(MachineId machineId) {
        return hasStoredCredential(getHostCredentialUseCase.getHostCredential(machineId));
    }

    /**
     * The same question asked of a credential already in hand — so {@code list()}, which needs the
     * credential anyway for the effective user, does not re-read the vault just to re-ask it. One
     * definition of "Vaier holds a usable login here"; two would be free to drift.
     */
    private static boolean hasStoredCredential(Optional<HostCredentialView> credential) {
        return credential.map(HostCredentialView::hasSecret).orElse(false);
    }

    /**
     * The Vaier server host as a machine (#311): its canonical name, effective SSH access, and
     * whether a host credential is stored for it. Feeds the dedicated Vaier-server card's SSH-access
     * toggle and credential control. Writes reuse {@code /machines/{machineId}/ssh-access} and
     * {@code /machines/{machineId}/ssh-credential} with the returned {@code name}.
     */
    @GetMapping("/vaier-server")
    public VaierServerResponse vaierServer() {
        Machine server = getVaierServerUseCase.getVaierServerMachine();
        return new VaierServerResponse(server.id().value(), server.name(), server.effectiveSshAccess(),
            hasStoredCredential(server.id()));
    }

    record VaierServerResponse(String id, String name, boolean sshAccess, boolean hasCredential) {}

    /**
     * The progressive-adoption nudges Vaier suggests for one machine: evidence-backed, single yes/no
     * prompts to adopt one more capability — publish its exposed services, back it up, or make it the
     * fleet's backup server. Each carries its own "why" from already-cached state.
     *
     * <p><b>Composed at the driving edge.</b> The controller gathers each signal from an existing
     * {@code *UseCase} — the machine, its publishable services, whether a credential is stored, whether
     * anything is backed up, the backup fleet, reachability — and hands them to the pure-domain
     * {@link MachineNudges} assembler, which owns the decisions. No application service reaches across
     * domains to collect nudges, and none implements a driven port to expose them. 404 when no machine
     * has that id. A non-whitelisted path under {@code /machines}, so it is admin-gated automatically.
     */
    @GetMapping("/{machineId}/nudges")
    public List<NudgeResponse> nudges(@PathVariable String machineId) {
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        MachineId id = MachineId.of(machineId);
        Machine target = machines.stream()
            .filter(m -> id.isSameAs(m.id()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException("Machine not found: " + machineId));

        // Which discovered services sit on THIS machine. It used to be resolved by name — the owner's
        // name matched against the machine's — which needed a Vaier-server name and an
        // address-to-name map just to do the matching, and answered "a machine called that" where the
        // question was "this machine". The feed carries the owner's identity now, so the domain answers
        // it directly and the two-map scaffolding is gone with the last Machine.hasSameName call.
        int publishableCount = (int) getPublishableServicesUseCase.getPublishableServices().stream()
            .filter(s -> s.belongsTo(target.id()))
            .count();
        boolean hasCredential = hasStoredCredential(target.id());
        boolean alreadyProtected = getBackupJobsUseCase.getBackupJobs().stream()
            .anyMatch(j -> target.id().equals(j.machineId()));
        BackupFleet fleet = new BackupFleet(getBackupServersUseCase.getBackupServers());
        Map<String, Reachability> lanReachability = target.lanAddress() == null ? Map.of()
            : Map.of(target.lanAddress(), getLanServerReachabilityUseCase.getReachability(target.lanAddress()));
        boolean reachable = target.isReachable(lanReachability);

        return MachineNudges.forMachine(target, publishableCount, reachable, hasCredential,
                alreadyProtected, fleet).stream()
            .map(NudgeResponse::from)
            .toList();
    }

    /** One nudge flattened for the browser: its kind, operator-facing title, evidence, and action hint. */
    public record NudgeResponse(String kind, String title, String evidence, String action) {
        static NudgeResponse from(MachineNudge n) {
            return new NudgeResponse(n.kind().name(), n.title(), n.evidence(), n.action());
        }
    }

    /**
     * Sets whether Vaier offers SSH for a machine — the credential control now, the web terminal
     * later. Writes an explicit override and returns the resulting effective state. 404 (via
     * {@code NotFoundException}) when no machine bears that name. Admin-gated (non-whitelisted path).
     */
    @PatchMapping("/{machineId}/ssh-access")
    public SshAccessResponse setSshAccess(@PathVariable String machineId,
                                          @RequestBody SshAccessRequest request) {
        boolean enabled = request != null && request.enabled();
        boolean effective = setMachineSshAccessUseCase.setMachineSshAccess(MachineId.of(machineId), enabled);
        return new SshAccessResponse(effective);
    }

    /**
     * A machine's filesystems, read now (#323 slice C, fixed by #325). {@code RemoteDiskWatcher} has computed
     * this on a schedule since the disk alerts shipped, but only ever emailed about it — and until #325 it
     * read {@code df -P /}, so it saw the root filesystem and only the root filesystem. On the NAS that is
     * the 2.3 GB DSM system partition (88% by design) while {@code /volume1} — 11.6 TB, every borg backup —
     * was invisible. So this returns <b>every</b> real filesystem.
     *
     * <p>A sibling of {@code /machines/{machineId}/files}: a non-whitelisted path under {@code /machines}, so
     * it sits behind the admin auth chain automatically. Reading a machine's disks is never anonymous.
     *
     * <p>A disk that cannot be read is a {@code DiskUnreadableException} → {@code 502}, carrying the reason
     * verbatim. It is never a {@code 0%} and never an empty list.
     */
    @GetMapping("/{machineId}/disk")
    public List<FilesystemResponse> disk(@PathVariable String machineId) {
        return getMachineDiskUsageUseCase.getDiskUsage(MachineId.of(machineId)).stream()
            .map(fs -> new FilesystemResponse(fs.machineName(), fs.device(), fs.mountPoint(),
                fs.sizeKb(), fs.usedKb(), fs.availableKb(), fs.size(), fs.available(),
                fs.usedPercent(), fs.thresholdPercent(), fs.watched(), fs.aboveThreshold()))
            .toList();
    }

    /**
     * Watch or mute one filesystem on one machine, optionally at its own threshold (#325).
     *
     * <p>The mount point travels in the <b>body</b>, not the path: a mount point contains slashes
     * ({@code /volume1}, and worse), and a path variable carrying them is a routing problem and an encoding
     * bug waiting to happen. In a body a slash is just a character.
     */
    @PutMapping("/{machineId}/disk/watch")
    public DiskWatchResponse setDiskWatch(@PathVariable String machineId,
                                          @RequestBody DiskWatchRequest request) {
        setDiskWatchUseCase.setDiskWatch(MachineId.of(machineId), request.mountPoint(), request.watched(),
            request.thresholdPercent());
        return new DiskWatchResponse(machineId, request.mountPoint(), request.watched(),
            request.thresholdPercent());
    }

    /**
     * One filesystem, with its size, the threshold it was judged against (its own or the global one), whether
     * Vaier watches it, and the domain's verdict on it. The verdict travels rather than the browser
     * recomputing it, so "under pressure" means one thing in the alert email and in the Explorer.
     *
     * <p>The raw {@code *Kb} block counts travel alongside the human-readable {@code size}/{@code available}
     * so a client can sort or graph on them without re-parsing a rendered string.
     */
    record FilesystemResponse(String machine, String device, String mountPoint,
                              long sizeKb, long usedKb, long availableKb,
                              String size, String available,
                              int usedPercent, int thresholdPercent, boolean watched,
                              boolean aboveThreshold) {}

    /** @param thresholdPercent this filesystem's own threshold (1–100), or null to use the global one. */
    record DiskWatchRequest(String mountPoint, boolean watched, Integer thresholdPercent) {}

    /** The watch as it now stands, echoed back so the Explorer can render it without a re-read. */
    record DiskWatchResponse(String machineId, String mountPoint, boolean watched,
                             Integer thresholdPercent) {}

    /**
     * Forget the pinned SSH host key for a machine (#308), so the next terminal connect re-pins on
     * first use. Use after a host is legitimately rebuilt and a host-key mismatch is refusing connects.
     */
    @DeleteMapping("/{machineId}/host-key")
    public ResponseEntity<Void> clearHostKey(@PathVariable String machineId) {
        clearHostKeyUseCase.clearHostKey(MachineId.of(machineId));
        return ResponseEntity.noContent().build();
    }

    record SshAccessRequest(boolean enabled) {}

    record SshAccessResponse(boolean sshAccess) {}

    public record MachineResponse(
        String id,
        String name,
        String type,
        String publicKey,
        String allowedIps,
        String endpointIp,
        String endpointPort,
        String latestHandshake,
        String transferRx,
        String transferTx,
        String lanCidr,
        String lanAddress,
        boolean runsDocker,
        Integer dockerPort,
        String deviceCategory,
        boolean sshAccess,
        boolean hasCredential,
        boolean vaierServer,
        SshServerPresence sshServerPresence,
        String effectiveUsername,
        boolean effectiveUserPrivileged
    ) {
        static MachineResponse from(Machine m, boolean hasCredential) {
            return from(m, hasCredential, false, SshServerPresence.UNKNOWN);
        }

        static MachineResponse from(Machine m, boolean hasCredential, boolean vaierServer,
                                    SshServerPresence sshServerPresence) {
            return from(m, hasCredential, vaierServer, sshServerPresence, null);
        }

        /**
         * @param effectiveUser the user Vaier acts as on this machine, or null when it holds no login for
         *                      it. Flattened onto the response: the decision travels as a boolean the
         *                      browser reads, never as a name it re-judges.
         */
        static MachineResponse from(Machine m, boolean hasCredential, boolean vaierServer,
                                    SshServerPresence sshServerPresence, EffectiveUser effectiveUser) {
            return new MachineResponse(
                m.id().value(),
                m.name(),
                m.type().name(),
                m.publicKey(),
                m.allowedIps(),
                m.endpointIp(),
                m.endpointPort(),
                m.latestHandshake(),
                m.transferRx(),
                m.transferTx(),
                m.lanCidr(),
                m.lanAddress(),
                m.runsDocker(),
                m.dockerPort(),
                m.deviceCategory().name(),
                m.effectiveSshAccess(),
                hasCredential,
                vaierServer,
                sshServerPresence,
                effectiveUser == null ? null : effectiveUser.username(),
                effectiveUser != null && effectiveUser.privileged()
            );
        }
    }
}
