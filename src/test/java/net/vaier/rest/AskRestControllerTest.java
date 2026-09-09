package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import net.vaier.domain.AskUnavailableException;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRun;
import net.vaier.domain.BackupRunStatus;
import net.vaier.domain.BlockDecision;
import net.vaier.domain.CommandOutcome;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.DockerService;
import net.vaier.domain.EnrolmentRequest;
import net.vaier.domain.Machine;
import net.vaier.domain.Reachability;
import net.vaier.domain.MachineDiskStanding;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.NoHostCredentialException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.ReverseProxyRoute.ServiceLocation;
import net.vaier.domain.Server.State;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The <b>Ask</b> endpoint (#360 slice 1). Its constructor is the honest list of everything Ask may read, and
 * the projections here are the whole of what leaves Vaier for the Claude API — so the test that matters most
 * is the one that reads every one of them and finds no secret in any.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AskRestControllerTest {

    @Mock AskUseCase askUseCase;
    @Mock IsAskAvailableUseCase isAskAvailableUseCase;
    @Mock GetLanServerReachabilityUseCase getLanServerReachabilityUseCase;
    @Mock GetMachinesUseCase getMachinesUseCase;
    @Mock GetVpnPeersUseCase getVpnPeersUseCase;
    @Mock ListEnrolmentRequestsUseCase listEnrolmentRequestsUseCase;
    @Mock GetPublishedServicesUseCase getPublishedServicesUseCase;
    @Mock GetBackupJobsUseCase getBackupJobsUseCase;
    @Mock GetBackupRunsUseCase getBackupRunsUseCase;
    @Mock GetMachineDiskStandingsUseCase getMachineDiskStandingsUseCase;
    @Mock DiscoverPeerContainersUseCase discoverPeerContainersUseCase;
    @Mock DiscoverVaierServerContainersUseCase discoverVaierServerContainersUseCase;
    @Mock GetBlockDecisionsUseCase getBlockDecisionsUseCase;
    @Mock RunReadOnlyCommandUseCase runReadOnlyCommandUseCase;

    private AskRestController controller;

    private static final MachineId COLINA = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");

    @BeforeEach
    void setUp() {
        controller = new AskRestController(askUseCase, isAskAvailableUseCase, getMachinesUseCase,
            getVpnPeersUseCase, listEnrolmentRequestsUseCase, getPublishedServicesUseCase,
            getBackupJobsUseCase, getBackupRunsUseCase, getMachineDiskStandingsUseCase,
            discoverPeerContainersUseCase, discoverVaierServerContainersUseCase, getBlockDecisionsUseCase,
            getLanServerReachabilityUseCase, runReadOnlyCommandUseCase, new ObjectMapper());
    }

    // --- is Ask offered at all -------------------------------------------------------------------------

    @Test
    void availability_answersWhetherAskMayBeUsed() {
        when(isAskAvailableUseCase.isAvailable()).thenReturn(true);

        ResponseEntity<AskRestController.AvailabilityResponse> response = controller.availability();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().available()).isTrue();
    }

    @Test
    void availability_saysNoWhenNoAnthropicApiKeyIsStored() {
        when(isAskAvailableUseCase.isAvailable()).thenReturn(false);

        assertThat(controller.availability().getBody().available()).isFalse();
    }

    // --- the answer, streamed --------------------------------------------------------------------------

    @Test
    void answer_sendsEachPieceOfTheAnswerAsATextEventThenDone() throws IOException {
        answering("Colina", " is red.");
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, "which machine is red?", List.of());

        assertThat(sentEvents(emitter)).containsExactly(
            "event:text\ndata:Colina\n\n",
            "event:text\ndata: is red.\n\n",
            "event:done\ndata:\n\n");
        verify(emitter).complete();
    }

    @Test
    void answer_passesTheQuestionAndTheConversationSoFar() {
        when(isAskAvailableUseCase.isAvailable()).thenReturn(true);
        answering("yes.");

        controller.ask(new AskRestController.AskRequest("and colina27?", List.of(
            new AskRestController.TurnRequest("OPERATOR", "is the nas up?"),
            new AskRestController.TurnRequest("VAIER", "yes, it answered a minute ago."))));

        ArgumentCaptor<List<ConversationTurn>> history = ArgumentCaptor.forClass(List.class);
        verify(askUseCase, timeout(2000)).ask(eq("and colina27?"), history.capture(), anyList(), any());
        assertThat(history.getValue()).containsExactly(
            new ConversationTurn(Role.OPERATOR, "is the nas up?"),
            new ConversationTurn(Role.VAIER, "yes, it answered a minute ago."));
    }

    /** A refusal reaches the pane as a sentence, not as a dropped stream the browser has to guess about. */
    @Test
    void answer_sendsTheRefusalAsAnErrorEventAndClosesCleanly() throws IOException {
        doThrow(new AskUnavailableException("Ask needs an Anthropic API key."))
            .when(askUseCase).ask(anyString(), anyList(), anyList(), any());
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, "anything?", List.of());

        assertThat(sentEvents(emitter))
            .containsExactly("event:error\ndata:Ask needs an Anthropic API key.\n\n");
        verify(emitter).complete();
    }

    // --- the tools ------------------------------------------------------------------------------------

    @Test
    void itOffersOneToolPerCatalogueEntry() {
        answering("ok");

        controller.answer(mock(SseEmitter.class), "anything?", List.of());

        assertThat(offeredTools()).extracting(offer -> offer.tool().toolName())
            .containsExactlyElementsOf(List.of(AskTool.values()).stream().map(AskTool::toolName).toList());
    }

    @Test
    void theFleetToolNamesEachMachineAndWhetherItIsReachable() {
        answering("ok");
        fleetOf();

        String fleet = read(AskTool.FLEET);

        assertThat(fleet).contains("Colina 27").contains("10.13.13.3").contains("UBUNTU_SERVER");
        assertThat(fleet).contains("\"standing\":\"connected\"");
    }

    @Test
    void theFleetToolJudgesALanServerByItsLanProbe_andTheVaierServerAsAlwaysThere() {
        // The live answer called the NAS and the Vaier server "not connected" — a peer's word, for machines
        // that never had a tunnel. The machine's own verdict knows the difference.
        answering("ok");
        MachineId nas = MachineId.of("11111111-1111-1111-1111-111111111111");
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(nas, "NAS", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
                null, "192.168.3.3", true, 2375, DeviceCategory.NAS, null),
            Machine.vaierServer(MachineId.of("22222222-2222-2222-2222-222222222222"), null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of());
        when(getLanServerReachabilityUseCase.getReachability("192.168.3.3")).thenReturn(Reachability.OK);

        String fleet = read(AskTool.FLEET);

        assertThat(fleet).contains("\"name\":\"NAS\"").contains("\"standing\":\"reachable\"");
        assertThat(fleet).contains("\"standing\":\"this server, always reachable\"");
        assertThat(fleet).doesNotContain("not connected");
    }

    @Test
    void theFleetToolSaysNotCheckedYet_forALanServerVaierHasNotProbedSinceItStarted() {
        // For the first minutes after a restart the LAN probe has no verdict. A missing fact is not a bad one.
        answering("ok");
        MachineId nas = MachineId.of("11111111-1111-1111-1111-111111111111");
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(
            new Machine(nas, "NAS", MachineType.LAN_SERVER, null, null, null, null, null, null, null,
                null, "192.168.3.3", true, 2375, DeviceCategory.NAS, null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of());
        when(getLanServerReachabilityUseCase.getReachability("192.168.3.3")).thenReturn(Reachability.UNKNOWN);

        assertThat(read(AskTool.FLEET)).contains("\"standing\":\"not checked yet\"").doesNotContain("unreachable");
    }

    @Test
    void theWaitingToJoinToolCarriesTheJoinCodeAndNeverTheTicketOrTheKey() {
        answering("ok");
        when(listEnrolmentRequestsUseCase.pending()).thenReturn(List.of(
            new EnrolmentRequest("4417", "a-32-byte-unguessable-ticket", "Ruten",
                "aGVsbG8td29ybGQtdGhpcy1pcy1hLXdnLWtleS0xMjM0NQ=", System.currentTimeMillis() + 300_000,
                null)));

        String waiting = read(AskTool.WAITING_TO_JOIN);

        assertThat(waiting).contains("4417").contains("Ruten");
        assertThat(waiting).doesNotContain("a-32-byte-unguessable-ticket");
        assertThat(waiting).doesNotContain("aGVsbG8td29ybGQ");
    }

    @Test
    void theBackupsToolSaysHowTheLastRunOfEachJobWent() {
        answering("ok");
        fleetOf();
        when(getBackupJobsUseCase.getBackupJobs()).thenReturn(List.of(BackupJob.builder()
            .name("colina27-home").machineId(COLINA).repositoryName("colina27")
            .sourcePaths(List.of("/home")).excludes(List.of()).compression("zstd,6").enabled(true)
            .keepDaily(7).keepWeekly(4).keepMonthly(6).build()));
        when(getBackupRunsUseCase.latestForMachine(COLINA)).thenReturn(Optional.of(new BackupRun(
            "run-1", "colina27-home", "colina27", COLINA, BackupRunStatus.WARNING,
            Instant.parse("2026-09-08T02:00:00Z"), Instant.parse("2026-09-08T02:14:00Z"), 1,
            "colina27-{now}", "1 file vanished during the backup")));

        String backups = read(AskTool.BACKUPS);

        assertThat(backups).contains("colina27-home").contains("WARNING").contains("Colina 27");
    }

    @Test
    void theDisksToolNamesTheFilesystemClosestToTrouble() {
        answering("ok");
        fleetOf();
        when(getMachineDiskStandingsUseCase.getMachineDiskStandings()).thenReturn(List.of(
            MachineDiskStanding.builder().machineId(COLINA).worstMountPoint("/volume1")
                .worstUsedPercent(86).worstThresholdPercent(85).breachingFilesystems(1)
                .watchedFilesystems(3).build()));

        String disks = read(AskTool.DISKS);

        assertThat(disks).contains("/volume1").contains("86").contains("Colina 27");
    }

    @Test
    void theContainerUpdatesToolListsOnlyContainersWantingANewerImage() {
        answering("ok");
        fleetOf();
        when(discoverPeerContainersUseCase.discoverAll()).thenReturn(List.of(new PeerContainers(
            COLINA.value(), "colina27", "10.13.13.3", "OK", List.of(
                new DockerService("c1", "grafana", "grafana/grafana:11.3.0", "11.3.0", List.of(),
                    List.of(), "running", "sha256:aaa", UpdateAvailability.UPDATE_AVAILABLE),
                new DockerService("c2", "mosquitto", "eclipse-mosquitto:2.1.2", "2.1.2", List.of(),
                    List.of(), "running", "sha256:bbb", UpdateAvailability.UP_TO_DATE)),
            false, null)));

        String updates = read(AskTool.CONTAINER_UPDATES);

        assertThat(updates).contains("grafana");
        assertThat(updates).doesNotContain("mosquitto");
    }

    @Test
    void theSecurityToolSaysWhoIsBeingKeptOut() {
        answering("ok");
        when(getBlockDecisionsUseCase.getBlockDecisions()).thenReturn(List.of(BlockDecision.builder()
            .id(11L).scenario("crowdsecurity/ssh-bf").sourceIp("203.0.113.7").type("ban")
            .duration("3h59m").country("RU").asnOrg("Example Telecom").build()));

        String security = read(AskTool.SECURITY);

        assertThat(security).contains("203.0.113.7").contains("crowdsecurity/ssh-bf");
    }

    @Test
    void thePublishedServicesToolSaysWhereEachServiceRunsAndWhetherItIsReachable() {
        answering("ok");
        when(getPublishedServicesUseCase.getPublishedServices()).thenReturn(List.of(
            new PublishedServiceUco("Grafana @ Colina 27", "Grafana", COLINA.value(), "Colina 27", null,
                ServiceLocation.PEER_SERVER, true, "grafana.example.com", "10.13.13.3", 3000, State.OK, true,
                null, false, false, null, false, null, null, null, null, null, "social", false, null)));

        String services = read(AskTool.PUBLISHED_SERVICES);

        assertThat(services).contains("Grafana").contains("Colina 27").contains("grafana.example.com");
    }

    /**
     * The one test that has to hold for the whole feature to be safe: every projection, for a fixture that
     * carries a secret in every field that could hold one, and not one of them comes out the other side.
     * The model never sees a key, and neither does Anthropic.
     */
    @Test
    void noToolEverRendersASecret() {
        answering("ok");
        fleetOf();
        when(listEnrolmentRequestsUseCase.pending()).thenReturn(List.of(
            new EnrolmentRequest("4417", "TICKET-SECRET", "Ruten", "PUBLICKEY-SECRET",
                System.currentTimeMillis() + 300_000, "CONFIGFILE-SECRET")));

        String everything = List.of(AskTool.values()).stream()
            .map(this::read)
            .collect(Collectors.joining("\n"));

        assertThat(everything)
            .doesNotContain("TICKET-SECRET", "PUBLICKEY-SECRET", "CONFIGFILE-SECRET", "PRESHARED-SECRET");
        assertThat(everything.toLowerCase()).doesNotContain(
            "publickey", "privatekey", "presharedkey", "passphrase", "password", "credential",
            "apikey", "ticket", "token", "secret", "configfile");
    }

    // --- run_on_machine: one looking command, on the machine the model named ----------------------------

    @Test
    void runOnMachine_findsTheMachineByName_andRunsThroughTheUseCase() {
        answering("ok");
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(COLINA, "apt list --upgradable"))
            .thenReturn(new CommandOutcome(0, false, "curl/noble-updates 8.5.0 amd64 [upgradable from: 8.4.0]", false));

        String fact = read(AskTool.RUN_ON_MACHINE, Map.of("machine", "colina 27", "command", "apt list --upgradable"));

        assertThat(fact).contains("Colina 27").contains("apt list --upgradable").contains("curl/noble-updates")
            .contains("\"exitCode\":0");
    }

    /** The domain's refusal is the answer, in its own words, so the model can say so. */
    @Test
    void runOnMachine_saysWhyARefusedCommandWasRefused() {
        answering("ok");
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(any(), anyString()))
            .thenThrow(new IllegalArgumentException("Ask can look, never change: apt install is not a looking command."));

        assertThat(read(AskTool.RUN_ON_MACHINE, Map.of("machine", "Colina 27", "command", "apt install vim")))
            .isEqualTo("Ask can look, never change: apt install is not a looking command.");
    }

    @Test
    void runOnMachine_saysWhenNoMachineHasThatName() {
        answering("ok");
        fleetOf();

        assertThat(read(AskTool.RUN_ON_MACHINE, Map.of("machine", "Apalveien", "command", "uptime")))
            .contains("no machine called \"Apalveien\"");
        verifyNoInteractions(runReadOnlyCommandUseCase);
    }

    /** A machine Vaier holds no login for cannot be reached; said plainly, and never as a stack trace. */
    @Test
    void runOnMachine_saysWhenVaierHoldsNoCredentialForTheMachine() {
        answering("ok");
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(any(), anyString()))
            .thenThrow(new NoHostCredentialException("Colina 27"));

        assertThat(read(AskTool.RUN_ON_MACHINE, Map.of("machine", "Colina 27", "command", "uptime")))
            .isEqualTo("No SSH credential is stored for Colina 27, so Vaier cannot run anything there.");
    }

    /**
     * A transport failure's own message can carry an address, a user or a path — the same reason the
     * emitter never repeats one — so it is answered in Vaier's words.
     */
    @Test
    void runOnMachine_neverRepeatsATransportFailuresOwnMessage() {
        answering("ok");
        fleetOf();
        when(runReadOnlyCommandUseCase.runReadOnly(any(), anyString()))
            .thenThrow(new SshConnectException("connect to 10.13.13.3:22 as geir failed"));

        String fact = read(AskTool.RUN_ON_MACHINE, Map.of("machine", "Colina 27", "command", "uptime"));

        assertThat(fact).isEqualTo("Colina 27 could not be reached over SSH.");
        assertThat(fact).doesNotContain("10.13.13.3").doesNotContain("geir");
    }

    // --- fixtures and plumbing -------------------------------------------------------------------------

    /** A one-machine fleet, connected, so every machine-keyed projection has a name to use. */
    private void fleetOf() {
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(new Machine(
            COLINA, "Colina 27", MachineType.UBUNTU_SERVER, "PUBLICKEY-SECRET", "10.13.13.3/32",
            "77.16.1.2", "51820", String.valueOf(System.currentTimeMillis() / 1000 - 30), "1.2 GiB", "3.4 GiB",
            "192.168.1.0/24", "192.168.1.10",
            true, 2375, DeviceCategory.SERVER, null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
            .id("colina27").machineId(COLINA.value()).name("Colina 27").publicKey("PUBLICKEY-SECRET")
            .tunnelIp("10.13.13.3").peerType(MachineType.UBUNTU_SERVER).connected(true)
            .lanCidr("192.168.1.0/24").description("the relay in the garage")
            .deviceCategory(DeviceCategory.SERVER).build()));
    }

    /** Answers the given chunks, so the tool offers are captured on a path that actually completes. */
    private void answering(String... chunks) {
        doAnswer(invocation -> {
            Consumer<String> onText = invocation.getArgument(3);
            for (String chunk : chunks) {
                onText.accept(chunk);
            }
            return null;
        }).when(askUseCase).ask(anyString(), anyList(), anyList(), any());
    }

    private List<ToolOffer> offeredTools() {
        ArgumentCaptor<List<ToolOffer>> tools = ArgumentCaptor.forClass(List.class);
        verify(askUseCase, atLeastOnce()).ask(anyString(), anyList(), tools.capture(), any());
        return tools.getValue();
    }

    /** Runs one question and reads back what the named tool would answer with. */
    private String read(AskTool tool) {
        controller.answer(mock(SseEmitter.class), "anything?", List.of());
        return offeredTools().stream()
            .filter(offer -> offer.tool() == tool)
            .findFirst().orElseThrow()
            .read().apply(Map.of());
    }

    /** As {@link #read(AskTool)}, with what the model said. */
    private String read(AskTool tool, Map<String, String> args) {
        controller.answer(mock(SseEmitter.class), "anything?", List.of());
        return offeredTools().stream()
            .filter(offer -> offer.tool() == tool)
            .findFirst().orElseThrow()
            .read().apply(args);
    }

    /** Every event the emitter was sent, exactly as it goes on the wire. */
    private List<String> sentEvents(SseEmitter emitter) throws IOException {
        ArgumentCaptor<SseEventBuilder> events = ArgumentCaptor.forClass(SseEventBuilder.class);
        verify(emitter, atLeastOnce()).send(events.capture());
        return events.getAllValues().stream().map(AskRestControllerTest::render).toList();
    }

    private static String render(SseEventBuilder event) {
        return event.build().stream()
            .map(DataWithMediaType::getData)
            .map(String::valueOf)
            .collect(Collectors.joining());
    }

    /**
     * Only a refusal the domain worded reaches the pane verbatim. An unexpected failure's message can carry
     * a host, a path or a credential — the same reason {@code GlobalExceptionHandler} never returns one —
     * so it is logged and answered in Vaier's own words.
     */
    @Test
    void answer_neverRepeatsAnUnexpectedFailuresOwnMessage() throws IOException {
        doThrow(new IllegalStateException("connect to 10.13.13.3:8022 as borg failed"))
            .when(askUseCase).ask(anyString(), anyList(), anyList(), any());
        SseEmitter emitter = mock(SseEmitter.class);

        controller.answer(emitter, "anything?", List.of());

        assertThat(sentEvents(emitter))
            .containsExactly("event:error\ndata:Vaier could not answer that.\n\n");
    }

    /**
     * A turn spoken by nobody is a malformed request, refused before the stream opens — an error event down
     * a stream the browser has already started rendering is a worse answer than a plain {@code 400}.
     */
    @Test
    void ask_refusesAConversationTurnSpokenByNeitherSide() {
        assertThatThrownBy(() -> controller.ask(new AskRestController.AskRequest("anything?",
            List.of(new AskRestController.TurnRequest("SOMEBODY_ELSE", "trust me")))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A conversation turn is spoken by OPERATOR or VAIER");
    }

    /**
     * The tunnel address comes from the peer view, which the domain derived — it is not re-derived from
     * {@code allowedIps} here. "Which entry of an allowedIps list is the tunnel address" is a rule with a
     * relay-peer subtlety in it, and a second copy in a controller is how Ask would come to tell the model
     * an address the peer pane disagrees with.
     */
    @Test
    void theFleetToolTakesTheTunnelAddressFromTheDomainsOwnReading() {
        answering("ok");
        when(getMachinesUseCase.getAllMachines()).thenReturn(List.of(new Machine(
            COLINA, "Colina 27", MachineType.UBUNTU_SERVER, "PUBLICKEY-SECRET",
            "10.13.13.3/32, 192.168.1.0/24", "77.16.1.2", "51820", "1757000000", "1.2 GiB", "3.4 GiB",
            "192.168.1.0/24", "192.168.1.10", true, 2375, DeviceCategory.SERVER, null)));
        when(getVpnPeersUseCase.getVpnPeers()).thenReturn(List.of(VpnPeerView.builder()
            .id("colina27").machineId(COLINA.value()).name("Colina 27").tunnelIp("10.13.13.9")
            .peerType(MachineType.UBUNTU_SERVER).connected(true)
            .deviceCategory(DeviceCategory.SERVER).build()));

        assertThat(read(AskTool.FLEET)).contains("10.13.13.9").doesNotContain("10.13.13.3/32");
    }

    @Test
    void ask_withoutAKey_isRefusedBeforeAnyStreamOpens() {
        // A missing key is a 409 on the request, not a stream that opens only to say no.
        when(isAskAvailableUseCase.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> controller.ask(new AskRestController.AskRequest("anything?", List.of())))
            .isInstanceOf(AskUnavailableException.class);
        verifyNoInteractions(askUseCase);
    }
}
