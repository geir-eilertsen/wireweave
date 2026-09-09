package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AskUseCase;
import net.vaier.application.DiscoverPeerContainersUseCase;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetBackupJobsUseCase;
import net.vaier.application.GetBackupRunsUseCase;
import net.vaier.application.GetBlockDecisionsUseCase;
import net.vaier.application.GetMachineDiskStandingsUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetLanServerReachabilityUseCase;
import net.vaier.application.GetPublishedServicesUseCase;
import net.vaier.application.GetPublishedServicesUseCase.PublishedServiceUco;
import net.vaier.application.GetVpnPeersUseCase;
import net.vaier.application.GetVpnPeersUseCase.VpnPeerView;
import net.vaier.application.IsAskAvailableUseCase;
import net.vaier.application.ListEnrolmentRequestsUseCase;
import net.vaier.application.RunReadOnlyCommandUseCase;
import net.vaier.domain.AskTool;
import net.vaier.domain.AskAvailability;
import net.vaier.domain.AskUnavailableException;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.DockerService;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.LanAnchor;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineType;
import net.vaier.domain.Reachability;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineReference;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * <b>Ask</b> (#360 slice 1): the operator's questions, answered from the fleet's own facts and streamed back
 * as they are written.
 *
 * <p>The constructor is the point. Every read Ask may make is a {@code *UseCase} named in it, so what the
 * model can be told about this fleet is a list anyone can review — and it is a list of <em>reads</em>. There
 * is no verb here at all. The one use case that reaches a machine runs a <b>Read-only command</b>, and what
 * counts as one is the domain's decision ({@code ReadOnlyCommand}), taken before anything is connected.
 *
 * <p>The projections below are the whole of what leaves Vaier for the Claude API, and they are deliberately
 * small: what a person would say out loud about a machine, a service or a backup. No key, no preshared key,
 * no config text, no credential, no passphrase, no token and no <b>Enrolment ticket</b> is in any of them,
 * and {@code AskRestControllerTest} reads every one of them back to prove it.
 */
@RestController
@RequestMapping("/ask")
@Slf4j
public class AskRestController {

    /** Long enough for a considered answer over a slow link; the pane says nothing while it waits. */
    private static final long ANSWER_TIMEOUT_MS = 300_000L;

    private final AskUseCase askUseCase;
    private final IsAskAvailableUseCase isAskAvailableUseCase;
    private final GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    private final GetMachinesUseCase getMachinesUseCase;
    private final GetVpnPeersUseCase getVpnPeersUseCase;
    private final ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    private final GetPublishedServicesUseCase getPublishedServicesUseCase;
    private final GetBackupJobsUseCase getBackupJobsUseCase;
    private final GetBackupRunsUseCase getBackupRunsUseCase;
    private final GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    private final DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    private final DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    private final GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    private final RunReadOnlyCommandUseCase runReadOnlyCommandUseCase;
    private final ObjectMapper objectMapper;

    /**
     * One thread, because a question is answered start to finish on it and Vaier answers one at a time.
     * The request thread must not be the one that waits: the answer takes tens of seconds.
     */
    private final ExecutorService answers = Executors.newSingleThreadExecutor(
        runnable -> new Thread(runnable, "vaier-ask"));

    public AskRestController(AskUseCase askUseCase,
                             IsAskAvailableUseCase isAskAvailableUseCase,
                             GetMachinesUseCase getMachinesUseCase,
                             GetVpnPeersUseCase getVpnPeersUseCase,
                             ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase,
                             GetPublishedServicesUseCase getPublishedServicesUseCase,
                             GetBackupJobsUseCase getBackupJobsUseCase,
                             GetBackupRunsUseCase getBackupRunsUseCase,
                             GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase,
                             DiscoverPeerContainersUseCase discoverPeerContainersUseCase,
                             DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase,
                             GetBlockDecisionsUseCase getBlockDecisionsUseCase,
                             GetLanServerReachabilityUseCase getLanServerReachabilityUseCase,
                             RunReadOnlyCommandUseCase runReadOnlyCommandUseCase,
                             ObjectMapper objectMapper) {
        this.getLanServerReachabilityUseCase = getLanServerReachabilityUseCase;
        this.askUseCase = askUseCase;
        this.isAskAvailableUseCase = isAskAvailableUseCase;
        this.getMachinesUseCase = getMachinesUseCase;
        this.getVpnPeersUseCase = getVpnPeersUseCase;
        this.listEnrolmentRequestsUseCase = listEnrolmentRequestsUseCase;
        this.getPublishedServicesUseCase = getPublishedServicesUseCase;
        this.getBackupJobsUseCase = getBackupJobsUseCase;
        this.getBackupRunsUseCase = getBackupRunsUseCase;
        this.getMachineDiskStandingsUseCase = getMachineDiskStandingsUseCase;
        this.discoverPeerContainersUseCase = discoverPeerContainersUseCase;
        this.discoverVaierServerContainersUseCase = discoverVaierServerContainersUseCase;
        this.getBlockDecisionsUseCase = getBlockDecisionsUseCase;
        this.runReadOnlyCommandUseCase = runReadOnlyCommandUseCase;
        this.objectMapper = objectMapper;
    }

    /** The answering thread does not outlive Vaier. */
    @PreDestroy
    void stopAnswering() {
        answers.shutdownNow();
    }

    /** Whether Ask is offered at all — the Explorer asks before drawing the pane in its menu. */
    @GetMapping("/availability")
    public ResponseEntity<AvailabilityResponse> availability() {
        return ResponseEntity.ok(new AvailabilityResponse(isAskAvailableUseCase.isAvailable()));
    }

    /**
     * Ask one question. The answer arrives as {@code text} events, one per piece, then {@code done}; a
     * refusal arrives as one {@code error} event carrying the sentence the operator can act on.
     */
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@RequestBody AskRequest request) {
        // Mapped here, on the request thread, so a malformed conversation is a plain 400 rather than an
        // error event down a stream the browser has already started rendering.
        List<ConversationTurn> history = turns(request.history());
        // Refused here too, so a missing key is a 409 and not a stream that opens only to say no.
        new AskAvailability(isAskAvailableUseCase.isAvailable()).requireAvailable();
        SseEmitter emitter = new SseEmitter(ANSWER_TIMEOUT_MS);
        answers.submit(() -> answer(emitter, request.question(), history));
        return emitter;
    }

    /** The whole of one answer, start to finish. Package-private so a test can drive it without a thread. */
    void answer(SseEmitter emitter, String question, List<ConversationTurn> history) {
        try {
            askUseCase.ask(question, history, toolOffers(),
                text -> send(emitter, "text", text));
            send(emitter, "done", "");
        } catch (Exception e) {
            log.warn("Ask could not answer: {}", e.toString());
            send(emitter, "error", messageFor(e));
        }
        emitter.complete();
    }

    /**
     * A refusal the domain worded is said as it stands; anything else is reported in Vaier's own words. An
     * unexpected failure's message can carry a host, a path or a credential — the same reason
     * {@link GlobalExceptionHandler} never returns one.
     */
    private static String messageFor(Exception e) {
        boolean worded = e instanceof AskUnavailableException || e instanceof IllegalArgumentException;
        return worded && e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage()
            : "Vaier could not answer that.";
    }

    private void send(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // The pane closed mid-answer. Ordinary, and nothing to recover: the answer had nowhere to go.
            log.debug("Ask stream closed before the answer finished ({})", e.toString());
        }
    }

    private static List<ConversationTurn> turns(List<TurnRequest> history) {
        return history == null ? List.of() : history.stream()
            .map(turn -> new ConversationTurn(roleOf(turn.role()), turn.text()))
            .toList();
    }

    /** Only two things speak in a conversation; anything else is a malformed request, said as such. */
    private static Role roleOf(String role) {
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("A conversation turn is spoken by OPERATOR or VAIER");
        }
    }

    // --- the tools ------------------------------------------------------------------------------------

    /** One offer per catalogue entry, in the catalogue's order. Only the command run reads its arguments. */
    private List<ToolOffer> toolOffers() {
        Map<AskTool, Function<Map<String, String>, String>> reads = new HashMap<>();
        reads.put(AskTool.FLEET, arguments -> readFleet());
        reads.put(AskTool.WAITING_TO_JOIN, arguments -> readWaitingToJoin());
        reads.put(AskTool.PUBLISHED_SERVICES, arguments -> readPublishedServices());
        reads.put(AskTool.BACKUPS, arguments -> readBackups());
        reads.put(AskTool.DISKS, arguments -> readDisks());
        reads.put(AskTool.CONTAINER_UPDATES, arguments -> readContainerUpdates());
        reads.put(AskTool.SECURITY, arguments -> readSecurity());
        reads.put(AskTool.RUN_ON_MACHINE, this::readRunOnMachine);

        List<ToolOffer> offers = new ArrayList<>();
        for (AskTool tool : AskTool.values()) {
            offers.add(new ToolOffer(tool, reads.get(tool)));
        }
        return offers;
    }

    private String readFleet() {
        Map<String, VpnPeerView> peers = new HashMap<>();
        for (VpnPeerView peer : getVpnPeersUseCase.getVpnPeers()) {
            if (peer.machineId() != null) {
                peers.put(peer.machineId(), peer);
            }
        }
        List<Machine> machines = getMachinesUseCase.getAllMachines();
        // What "reachable" means differs by kind of machine — the tunnel for a peer, the cached LAN probe for
        // a LAN server, always for the Vaier server — and the machine itself already knows. It reads the LAN
        // signal from the same cache the Explorer does; nothing is probed to answer a question.
        Map<String, Reachability> lan = new HashMap<>();
        for (Machine machine : machines) {
            if (machine.type() == MachineType.LAN_SERVER && machine.lanAddress() != null) {
                lan.put(machine.lanAddress(), getLanServerReachabilityUseCase.getReachability(machine.lanAddress()));
            }
        }
        return asJson(machines.stream()
            .map(machine -> MachineFact.of(machine, peers.get(machine.id().value()), standingOf(machine, lan)))
            .toList());
    }

    private String readWaitingToJoin() {
        long now = System.currentTimeMillis();
        return asJson(listEnrolmentRequestsUseCase.pending().stream()
            .map(request -> WaitingPhoneFact.of(request, now))
            .toList());
    }

    private String readPublishedServices() {
        return asJson(getPublishedServicesUseCase.getPublishedServices().stream()
            .map(ServiceFact::of)
            .toList());
    }

    private String readBackups() {
        Map<String, String> names = machineNames();
        return asJson(getBackupJobsUseCase.getBackupJobs().stream()
            .map(job -> BackupFact.of(job, names.get(job.machineId().value()),
                getBackupRunsUseCase.latestForMachine(job.machineId())))
            .toList());
    }

    private String readDisks() {
        Map<String, String> names = machineNames();
        return asJson(getMachineDiskStandingsUseCase.getMachineDiskStandings().stream()
            .map(standing -> DiskFact.of(standing, names.get(standing.machineId().value())))
            .toList());
    }

    /** Only what wants pulling. A list of every container the fleet runs would answer a different question. */
    private String readContainerUpdates() {
        Map<String, String> names = machineNames();
        List<ContainerUpdateFact> wanting = new ArrayList<>();
        for (PeerContainers peer : discoverPeerContainersUseCase.discoverAll()) {
            String machine = names.getOrDefault(peer.machineId(), peer.peerId());
            wanting.addAll(outdated(machine, peer.containers()));
        }
        wanting.addAll(outdated("the Vaier server", discoverVaierServerContainersUseCase.discover()));
        return asJson(wanting);
    }

    private static List<ContainerUpdateFact> outdated(String machine, List<DockerService> containers) {
        return containers == null ? List.of() : containers.stream()
            .filter(container -> container.updateAvailable().isUpdateAvailable())
            .map(container -> ContainerUpdateFact.of(machine, container))
            .toList();
    }

    private String readSecurity() {
        return asJson(getBlockDecisionsUseCase.getBlockDecisions().stream()
            .map(BlockFact::of)
            .toList());
    }

    /**
     * One command on the machine the model named. Every way this can fail is answered in a sentence the
     * model can repeat: the domain's own refusal verbatim, a name no machine has, a machine Vaier holds no
     * login for. A transport failure's own message can carry an address, a user or a path, so that one is
     * said in Vaier's words — the same reason the emitter never repeats one.
     */
    private String readRunOnMachine(Map<String, String> arguments) {
        Machine machine;
        try {
            machine = new MachineReference(arguments.get("machine")).resolve(getMachinesUseCase.getAllMachines());
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
        String command = arguments.getOrDefault("command", "");
        try {
            CommandOutcome outcome = runReadOnlyCommandUseCase.runReadOnly(machine.id(), command);
            return asJson(CommandFact.of(machine, command, outcome));
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        } catch (NoHostCredentialException e) {
            return "No SSH credential is stored for " + machine.name() + ", so Vaier cannot run anything there.";
        } catch (RuntimeException e) {
            log.warn("Ask could not run a command on {}: {}", machine.name(), e.toString());
            return machine.name() + " could not be reached over SSH.";
        }
    }

    /** Machine identities to the names the Explorer shows, so every projection says what is on screen. */
    private Map<String, String> machineNames() {
        Map<String, String> names = new HashMap<>();
        for (Machine machine : getMachinesUseCase.getAllMachines()) {
            names.put(machine.id().value(), machine.name());
        }
        return names;
    }

    private String asJson(Object projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (Exception e) {
            log.warn("Ask could not render a tool result", e);
            return "Vaier could not read that.";
        }
    }

    // --- what the model is told ------------------------------------------------------------------------

    record AvailabilityResponse(boolean available) {}

    record AskRequest(String question, List<TurnRequest> history) {}

    record TurnRequest(String role, String text) {}

    /**
     * A machine as a person would describe it. No public key, no allowed IPs, no endpoint.
     *
     * <p>The tunnel address comes from the peer view, which the domain already derived — never re-read off
     * {@code allowedIps} here; a LAN server has no peer and so no tunnel address. Whether the machine is
     * reachable is the machine's own verdict ({@link Machine#isReachable}), so a LAN server or the Vaier
     * server is never called "not connected" for lacking a tunnel it was never meant to have.
     */
    record MachineFact(String id, String name, String type, String tunnelIp, String lanCidr, String standing) {
        static MachineFact of(Machine machine, VpnPeerView peer, String standing) {
            return new MachineFact(machine.id() == null ? null : machine.id().value(), machine.name(),
                machine.type().name(),
                peer == null ? null : peer.tunnelIp(), machine.lanCidr(), standing);
        }
    }

    /**
     * The machine's verdict on itself, in a word the model can repeat. A LAN server that has not been probed
     * yet — the minutes after a restart — is "not checked yet", never "unreachable": the fact is missing,
     * not bad, and the difference is exactly what an operator asks about.
     */
    private static String standingOf(Machine machine, Map<String, Reachability> lan) {
        if (LanAnchor.VAIER_SERVER_NAME.equals(machine.name())) return "this server, always reachable";
        if (machine.type() == MachineType.LAN_SERVER) {
            Reachability r = lan.getOrDefault(machine.lanAddress(), Reachability.UNKNOWN);
            return r == Reachability.OK ? "reachable" : r == Reachability.DOWN ? "unreachable" : "not checked yet";
        }
        return machine.isReachable(lan) ? "connected" : "not connected";
    }

    /** A phone waiting to be let in: its name, its join code, and how long it has. Never its ticket or key. */
    record WaitingPhoneFact(String name, String joinCode, long minutesLeft) {
        static WaitingPhoneFact of(EnrolmentRequest request, long nowEpochMs) {
            return new WaitingPhoneFact(request.name(), request.code(),
                Math.round(request.secondsLeft(nowEpochMs) / 60.0));
        }
    }

    record ServiceFact(String name, String machine, String address, boolean reachable) {
        static ServiceFact of(PublishedServiceUco service) {
            return new ServiceFact(service.shortName(), service.hostName(), service.dnsAddress(),
                service.healthy());
        }
    }

    record BackupFact(String job, String machine, boolean enabled, String lastRun, String lastRunAt,
                      String lastRunNote) {
        static BackupFact of(BackupJob job, String machine, Optional<BackupRun> latest) {
            return new BackupFact(job.name(), machine, job.enabled(),
                latest.map(run -> run.status().name()).orElse("never run"),
                latest.map(BackupRun::finishedAt).map(String::valueOf).orElse(null),
                latest.map(BackupRun::summary).orElse(null));
        }
    }

    record DiskFact(String machine, String fullestFilesystem, int usedPercent, int alertsAbovePercent,
                    int filesystemsOverTheirThreshold) {
        static DiskFact of(MachineDiskStanding standing, String machine) {
            return new DiskFact(machine, standing.worstMountPoint(), standing.worstUsedPercent(),
                standing.worstThresholdPercent(), standing.breachingFilesystems());
        }
    }

    record ContainerUpdateFact(String machine, String container, String image) {
        static ContainerUpdateFact of(String machine, DockerService container) {
            return new ContainerUpdateFact(machine, container.containerName(), container.image());
        }
    }

    /** What one command printed on one machine. The command is echoed so a follow-up knows what was run. */
    record CommandFact(String machine, String command, int exitCode, boolean timedOut, String output, boolean cut) {
        static CommandFact of(Machine machine, String command, CommandOutcome outcome) {
            return new CommandFact(machine.name(), command, outcome.exitCode(), outcome.timedOut(),
                outcome.output(), outcome.cut());
        }
    }

    record BlockFact(String address, String why, String forHowLong, String country, String network) {
        static BlockFact of(BlockDecision decision) {
            return new BlockFact(decision.sourceIp(), decision.scenario(), decision.duration(),
                decision.country(), decision.asnOrg());
        }
    }
}
