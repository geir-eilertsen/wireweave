package net.fjordomatic.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fjordomatic.domain.port.ForPublishingEvents;
import net.fjordomatic.domain.port.ForStoringContainerSnapshots;
import net.fjordomatic.domain.port.ForRunningSshCommands;
import net.fjordomatic.domain.port.ForTrackingHostKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The update itself: which container may be updated, what compose is asked to do about it, and how
 * the two runs read as an outcome.
 */
class ContainerUpdateTest {

    private static final MachineId MACHINE = TestMachineIds.of("apalveien5");

    private final ForRunningSshCommands ssh = mock(ForRunningSshCommands.class);
    private final ForTrackingHostKeys hostKeys = mock(ForTrackingHostKeys.class);

    private static final SshTarget TARGET =
        new SshTarget("10.13.13.6", 22, "ubuntu", AuthMethod.PASSWORD, "secret", null, "SHA256:pinned", MACHINE);

    private static DockerService container(String name, ComposeCoordinates coordinates,
                                           ContainerUpdateEligibility eligibility) {
        return DockerService.builder()
            .containerId("id-" + name)
            .containerName(name)
            .image("vaultwarden/server:latest")
            .version("latest")
            .ports(List.of())
            .networks(List.of())
            .state("running")
            .composeCoordinates(coordinates)
            .updateEligibility(eligibility)
            .build();
    }

    private static ComposeCoordinates coordinates() {
        return new ComposeCoordinates("vaultwarden", "vaultwarden",
            List.of("/home/ubuntu/vaultwarden/docker-compose.yml"), "/home/ubuntu/vaultwarden");
    }

    private static CommandResult ok() {
        return new CommandResult(0, "", "", false, "SHA256:pinned");
    }

    // --- which container, and whether it may be updated at all ---

    @Test
    void of_findsTheNamedContainerOnTheMachine_andCarriesItsComposeCoordinates() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("paperless", coordinates(), ContainerUpdateEligibility.UPDATABLE),
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.machineId()).isEqualTo(MACHINE);
        assertThat(update.containerName()).isEqualTo("vaultwarden");
        assertThat(update.coordinates()).isEqualTo(coordinates());
    }

    @Test
    void of_noContainerOfThatNameOnTheMachine_isNotFound() {
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("paperless", coordinates(), ContainerUpdateEligibility.UPDATABLE))))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("vaultwarden");
    }

    @Test
    void of_withoutAContainerName_isARequestFjordCannotRead_notAMissingContainer() {
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, null, List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, "  ", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_notComposeManaged_isRefusedWithTheReasonSaidPlainly() {
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, "pihole", List.of(
            container("pihole", null, ContainerUpdateEligibility.NOT_COMPOSE_MANAGED))))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("pihole")
            .hasMessageContaining("how it was started");
    }

    @Test
    void of_fjordOwnStack_isRefusedWithItsOwnReason() {
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, "traefik", List.of(
            container("traefik", coordinates(), ContainerUpdateEligibility.FJORD_OWN_STACK))))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("traefik")
            .hasMessageContaining("Fjord release");
    }

    @Test
    void of_unjudgedContainer_isRefused_becauseNoVerdictIsNeverPermission() {
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), null))))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void of_eligibleButWithoutCoordinates_isRefusedRatherThanBuildingAHalfCommand() {
        assertThatThrownBy(() -> ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", null, ContainerUpdateEligibility.UPDATABLE))))
            .isInstanceOf(ConflictException.class);
    }

    // --- the two commands ---

    @Test
    void pullCommand_namesTheProjectDirectoryProjectEveryFileAndTheService_eachQuoted() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.pullCommand()).isEqualTo(
            "docker compose --project-directory '/home/ubuntu/vaultwarden' -p 'vaultwarden'"
                + " -f '/home/ubuntu/vaultwarden/docker-compose.yml' pull 'vaultwarden'");
    }

    @Test
    void recreateCommand_isTheSamePrefixWithUpMinusD() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.recreateCommand()).isEqualTo(
            "docker compose --project-directory '/home/ubuntu/vaultwarden' -p 'vaultwarden'"
                + " -f '/home/ubuntu/vaultwarden/docker-compose.yml' up -d 'vaultwarden'");
    }

    @Test
    void everyComposeFileAppearsInOrder_soAnOverrideFileIsNeverDropped() {
        ComposeCoordinates multiFile = new ComposeCoordinates("openhab", "openhab",
            List.of("/srv/openhab/docker-compose.yml", "/srv/openhab/docker-compose.override.yml"),
            "/srv/openhab");
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "openhab", List.of(
            container("openhab", multiFile, ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.pullCommand()).contains(
            "-f '/srv/openhab/docker-compose.yml' -f '/srv/openhab/docker-compose.override.yml'");
    }

    @Test
    void noWorkingDir_omitsProjectDirectoryEntirely() {
        ComposeCoordinates noDir = new ComposeCoordinates("openhab", "openhab",
            List.of("/srv/openhab/docker-compose.yml"), null);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "openhab", List.of(
            container("openhab", noDir, ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.pullCommand()).isEqualTo(
            "docker compose -p 'openhab' -f '/srv/openhab/docker-compose.yml' pull 'openhab'");
        assertThat(update.pullCommand()).doesNotContain("--project-directory");
    }

    // --- the labels are untrusted input ---

    @Test
    void aHostileComposeLabelNeverBecomesComposeCoordinates_soTheContainerIsNotComposeManaged() {
        Optional<ComposeCoordinates> hostile = ComposeCoordinates.fromLabels(Map.of(
            "com.docker.compose.project", "evil",
            "com.docker.compose.service", "evil",
            "com.docker.compose.project.config_files", "/tmp/x.yml'; rm -rf / #",
            "com.docker.compose.project.working_dir", "/tmp"));

        assertThat(hostile).isEmpty();
    }

    @Test
    void aPathWithASpaceStaysOneArgument_ratherThanBeingRefused() {
        ComposeCoordinates spaced = new ComposeCoordinates("stack", "app",
            List.of("/home/ubuntu/my stack/docker-compose.yml"), "/home/ubuntu/my stack");
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "app", List.of(
            container("app", spaced, ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.pullCommand()).isEqualTo(
            "docker compose --project-directory '/home/ubuntu/my stack' -p 'stack'"
                + " -f '/home/ubuntu/my stack/docker-compose.yml' pull 'app'");
    }

    @Test
    void aValueCarryingASingleQuoteIsRefusedOutright_ratherThanQuotedIntoASecondArgument() {
        // Reaching a single quote here means something built ComposeCoordinates without the label
        // validation; the command builder refuses rather than emitting a string that breaks out.
        ComposeCoordinates smuggled = new ComposeCoordinates("stack", "app",
            List.of("/tmp/a'; rm -rf /; echo '.yml"), "/tmp");
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "app", List.of(
            container("app", smuggled, ContainerUpdateEligibility.UPDATABLE)));

        assertThatThrownBy(update::pullCommand).isInstanceOf(IllegalArgumentException.class);
    }

    // --- running it ---

    @Test
    void run_pullsThenRecreates_andReadsAsUpdated() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any())).thenReturn(ok());

        ContainerUpdateOutcome outcome = update.carryOut(TARGET, ssh, hostKeys).outcome();

        assertThat(outcome).isEqualTo(ContainerUpdateOutcome.UPDATED);
        var order = inOrder(ssh);
        order.verify(ssh).run(TARGET, update.pullCommand(), ContainerUpdate.PULL_TIMEOUT);
        order.verify(ssh).run(TARGET, update.recreateCommand(), ContainerUpdate.RECREATE_TIMEOUT);
    }

    @Test
    void pullTimeoutIsMinutesNotSeconds_becauseAnImageIsBig() {
        assertThat(ContainerUpdate.PULL_TIMEOUT).isGreaterThanOrEqualTo(Duration.ofMinutes(5));
        assertThat(ContainerUpdate.RECREATE_TIMEOUT).isGreaterThanOrEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void aFailedPullNeverRecreates_soTheOldContainerIsLeftAlone() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(eq(TARGET), eq(update.pullCommand()), any()))
            .thenReturn(new CommandResult(1, "", "manifest unknown", false, "SHA256:pinned"));

        ContainerUpdateOutcome outcome = update.carryOut(TARGET, ssh, hostKeys).outcome();

        assertThat(outcome).isEqualTo(ContainerUpdateOutcome.PULL_FAILED);
        verify(ssh, never()).run(any(), eq(update.recreateCommand()), any());
    }

    @Test
    void aFailedRecreateIsItsOwnOutcome_becauseTheOldContainerIsStillRunning() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(eq(TARGET), eq(update.pullCommand()), any())).thenReturn(ok());
        when(ssh.run(eq(TARGET), eq(update.recreateCommand()), any()))
            .thenReturn(new CommandResult(1, "", "port is already allocated", false, "SHA256:pinned"));

        ContainerUpdateOutcome outcome = update.carryOut(TARGET, ssh, hostKeys).outcome();

        assertThat(outcome).isEqualTo(ContainerUpdateOutcome.RECREATE_FAILED);
    }

    @Test
    void aPullThatOutlastsItsDeadlineReadsAsTimedOut_andNothingIsRecreated() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(eq(TARGET), eq(update.pullCommand()), any()))
            .thenReturn(new CommandResult(-1, "", "", true, "SHA256:pinned"));

        ContainerUpdateOutcome outcome = update.carryOut(TARGET, ssh, hostKeys).outcome();

        assertThat(outcome).isEqualTo(ContainerUpdateOutcome.TIMED_OUT);
        verify(ssh, never()).run(any(), eq(update.recreateCommand()), any());
    }

    @Test
    void firstUseOfAMachinePinsWhatTheHostPresented() {
        SshTarget unpinned = new SshTarget("10.13.13.6", 22, "ubuntu", AuthMethod.PASSWORD,
            "secret", null, null, MACHINE);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any()))
            .thenReturn(new CommandResult(0, "", "", false, "SHA256:presented"));

        update.carryOut(unpinned, ssh, hostKeys);

        verify(hostKeys).pin(MACHINE, "SHA256:presented");
    }

    @Test
    void anAlreadyPinnedMachineIsNotRePinned() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any())).thenReturn(ok());

        update.carryOut(TARGET, ssh, hostKeys);

        verifyNoInteractions(hostKeys);
    }

    // --- an attempt that fails is an outcome too, never an escaping exception ---

    @Test
    void anSshFailureIsReadAsUnreachable_ratherThanEscapingToWhoeverCarriedTheUpdateOut() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any()))
            .thenThrow(new SshConnectException("connection refused by 10.13.13.6", new RuntimeException()));

        ContainerUpdate.Settlement settlement = update.carryOut(TARGET, ssh, hostKeys);

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.UNREACHABLE);
    }

    @Test
    void aFailedAttemptCarriesTheFailuresOwnWordsOut_soTheReasonSurvivesIntoTheLog() {
        // The domain does not log — one domain class in the whole codebase does. The reason therefore has
        // to leave the domain as data, or an operator debugging a failed update has nothing to read.
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any()))
            .thenThrow(new SshConnectException("connection refused by 10.13.13.6", new RuntimeException()));

        ContainerUpdate.Settlement settlement = update.carryOut(TARGET, ssh, hostKeys);

        assertThat(settlement.diagnostic())
            .contains("connection refused by 10.13.13.6")
            .contains("SshConnectException");
    }

    @Test
    void afailureNobodyAnticipatedStillSettles_andStillNamesItself() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        // No message at all — the shape a bug takes, as opposed to a failure Fjord anticipated.
        when(ssh.run(any(), anyString(), any())).thenThrow(new IllegalStateException());

        ContainerUpdate.Settlement settlement = update.carryOut(TARGET, ssh, hostKeys);

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.UNREACHABLE);
        assertThat(settlement.diagnostic()).contains("IllegalStateException");
    }

    @Test
    void anUpdateThatRanCarriesNoDiagnostic_becauseNothingWentWrongToExplain() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any())).thenReturn(ok());

        assertThat(update.carryOut(TARGET, ssh, hostKeys).diagnostic()).isNull();
    }

    // --- a failed command's own words are the answer, and must come out with it ---

    /** Carry out an update whose every command returns {@code result}. */
    private ContainerUpdate.Settlement settlementFrom(CommandResult result) {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any())).thenReturn(result);
        return update.carryOut(TARGET, ssh, hostKeys);
    }

    @Test
    void aFailedPullCarriesTheHostsOwnWords_becauseComposeStderrIsTheAnswer() {
        // "PULL_FAILED and nothing else" is the fact without the reason: the operator cannot tell a
        // docker-group problem from an unreadable compose file from a registry they cannot reach.
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(1, "",
            "Error response from daemon: pull access denied for netdata", false, "SHA256:pinned"));

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.PULL_FAILED);
        assertThat(settlement.diagnostic()).contains("pull access denied for netdata");
    }

    @Test
    void whenStderrIsEmptyTheWordsAreTakenFromStdout_becauseComposeIsNotConsistent() {
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(1,
            "no configuration file provided: not found", "", false, "SHA256:pinned"));

        assertThat(settlement.diagnostic()).contains("no configuration file provided");
    }

    @Test
    void theDiagnosticIsTheLastMeaningfulLine_becauseComposeChattersAboveItsError() {
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(1, "",
            """
            netdata Pulling
             a1b2c3 Waiting
             d4e5f6 Waiting

            Error response from daemon: manifest for netdata:latest not found

            """, false, "SHA256:pinned"));

        assertThat(settlement.diagnostic()).isEqualTo(
            "Error response from daemon: manifest for netdata:latest not found");
    }

    @Test
    void theDiagnosticIsBounded_becauseAToastIsNotAOneMebibyteStream() {
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(1, "",
            "Error: " + "x".repeat(5000), false, "SHA256:pinned"));

        assertThat(settlement.diagnostic()).hasSizeLessThanOrEqualTo(ContainerUpdate.Settlement.MAX_DIAGNOSTIC);
        assertThat(settlement.diagnostic()).startsWith("Error: ").endsWith("…");
    }

    @Test
    void composesColourCodesAreStripped_ratherThanShippedToATextOnlyToast() {
        // Compose colours its errors. "\033[31m" is an escape character the browser renders as nothing
        // useful and the log as mojibake, and it is a control character in a hand-rolled JSON string.
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(1, "",
            "\033[31mError response from daemon: manifest unknown\033[0m", false, "SHA256:pinned"));

        assertThat(settlement.diagnostic())
            .doesNotContain("\033")
            .contains("Error response from daemon: manifest unknown");
    }

    @Test
    void aFailedRecreateCarriesItsWordsToo() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(eq(TARGET), eq(update.pullCommand()), any())).thenReturn(ok());
        when(ssh.run(eq(TARGET), eq(update.recreateCommand()), any())).thenReturn(new CommandResult(1, "",
            "Error: driver failed programming external connectivity: port is already allocated",
            false, "SHA256:pinned"));

        ContainerUpdate.Settlement settlement = update.carryOut(TARGET, ssh, hostKeys);

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.RECREATE_FAILED);
        assertThat(settlement.diagnostic()).contains("port is already allocated");
    }

    @Test
    void aTimeoutSaysWhateverItManagedToCapture_beforeFjordStoppedWaiting() {
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(-1,
            "netdata Pulling\n a1b2c3 Downloading  412MB/1.2GB", "", true, "SHA256:pinned"));

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.TIMED_OUT);
        assertThat(settlement.diagnostic()).contains("Downloading");
    }

    @Test
    void aFailureThatSaidNothingAtAllCarriesNoDiagnostic_ratherThanAnEmptyOne() {
        ContainerUpdate.Settlement settlement =
            settlementFrom(new CommandResult(1, "  \n\n", "   ", false, "SHA256:pinned"));

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.PULL_FAILED);
        assertThat(settlement.diagnostic()).isNull();
    }

    @Test
    void anUpdateThatWorkedCarriesNoDiagnostic_becauseSuccessChatterExplainsNothing() {
        ContainerUpdate.Settlement settlement = settlementFrom(
            new CommandResult(0, "Container vaultwarden Started", "", false, "SHA256:pinned"));

        assertThat(settlement.outcome()).isEqualTo(ContainerUpdateOutcome.UPDATED);
        assertThat(settlement.diagnostic()).isNull();
    }

    @Test
    void theSentenceKeepsTheReassuranceAndAddsTheReason() {
        // Both halves matter: WHY it failed, and that the old container is still running. Losing the
        // second to make room for the first would trade one silence for another.
        ContainerUpdate.Settlement settlement = settlementFrom(new CommandResult(1, "",
            "Error response from daemon: pull access denied for netdata", false, "SHA256:pinned"));

        assertThat(settlement.sentenceFor("netdata"))
            .contains("still running")
            .contains("pull access denied for netdata");
    }

    @Test
    void aCleanOutcomesSentenceIsUnchanged_withNothingBoltedOntoIt() {
        ContainerUpdate.Settlement settlement = settlementFrom(
            new CommandResult(0, "Container vaultwarden Started", "", false, "SHA256:pinned"));

        assertThat(settlement.sentenceFor("vaultwarden"))
            .isEqualTo(ContainerUpdateOutcome.UPDATED.sentence("vaultwarden"));
    }

    // --- what an update does to the remembered update verdict ---

    @Test
    void of_carriesTheContainersImage_ratherThanLeavingItToBeReDerived() {
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        assertThat(update.image()).isEqualTo("vaultwarden/server:latest");
    }

    @Test
    void anUpdatedContainersImageIsNoLongerKnownToBeOutdated_soItsVerdictIsForgotten() {
        // The sweep remembers its verdict per (machine, image TAG), and an update changes the digest and
        // not the tag — so without this the yellow mark outlives the update that resolved it, forever.
        ForStoringContainerSnapshots snapshots = mock(ForStoringContainerSnapshots.class);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        update.forgetOutdatedVerdict(ContainerUpdateOutcome.UPDATED, snapshots);

        verify(snapshots).forgetImageUpdateVerdict(
            new ScopedImage(MACHINE.value(), "vaultwarden/server:latest"));
    }

    @Test
    void theVerdictIsForgotten_neverStampedUpToDate_becauseNothingWasReMeasured() {
        // Fjord pulled and recreated; it did not compare the registry's digest against the new container's.
        // Forgetting leaves UNKNOWN — "no sweep has judged this" — which is the truth. Stamping up to date
        // would be a verdict Fjord never took.
        ForStoringContainerSnapshots snapshots = mock(ForStoringContainerSnapshots.class);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        update.forgetOutdatedVerdict(ContainerUpdateOutcome.UPDATED, snapshots);

        verify(snapshots, never()).storeImageUpdateVerdicts(any());
    }

    @ParameterizedTest
    @EnumSource(value = ContainerUpdateOutcome.class,
        names = {"PULL_FAILED", "RECREATE_FAILED", "TIMED_OUT", "UNREACHABLE"})
    void anUpdateThatDidNotHappen_leavesTheVerdictExactlyAsItWas(ContainerUpdateOutcome outcome) {
        // The container is still running the image it had, so the mark is still true. Clearing it here
        // would be a lie in the other direction — and the operator would stop being told to act.
        ForStoringContainerSnapshots snapshots = mock(ForStoringContainerSnapshots.class);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        update.forgetOutdatedVerdict(outcome, snapshots);

        verifyNoInteractions(snapshots);
    }

    // --- announcing the settled update ---

    @Test
    void announce_pushesTheSettledOutcomeOnTheStreamTheExplorerAlreadyHoldsOpen() {
        ForPublishingEvents events = mock(ForPublishingEvents.class);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        update.announce(new ContainerUpdate.Settlement(ContainerUpdateOutcome.UPDATED, null), events);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(events).publish(eq("vpn-peers"), eq("container-update-settled"), data.capture());
        assertThat(data.getValue())
            .contains("\"machineId\":\"" + MACHINE.value() + "\"")
            .contains("\"containerName\":\"vaultwarden\"")
            .contains("\"outcome\":\"UPDATED\"")
            .contains("\"message\":\"" + ContainerUpdateOutcome.UPDATED.sentence("vaultwarden") + "\"");
    }

    @Test
    void theSettledPayloadSurvivesAHostThatSpokeInNewlinesTabsAndQuotes() throws Exception {
        // The payload is hand-rolled JSON delivered over SSE, and the browser JSON.parses it inside a
        // try/catch: a raw newline would break both the JSON and the SSE framing, and the operator would
        // get NOTHING — a worse silence than the one carrying the reason is meant to end.
        ForPublishingEvents events = mock(ForPublishingEvents.class);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));
        when(ssh.run(any(), anyString(), any())).thenReturn(new CommandResult(1, "",
            "chatter\n\tError: unable to read \"/srv/my stack/compose.yml\"[0m\n", false, "SHA256:x"));

        update.announce(update.carryOut(TARGET, ssh, hostKeys), events);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(events).publish(eq("vpn-peers"), eq("container-update-settled"), data.capture());
        String payload = data.getValue();
        assertThat(payload).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        JsonNode parsed = new ObjectMapper().readTree(payload);
        assertThat(parsed.get("outcome").asText()).isEqualTo("PULL_FAILED");
        assertThat(parsed.get("message").asText())
            .contains("still running")
            .contains("unable to read");
    }

    @Test
    void announce_carriesTheFailureSentenceVerbatim_soTheBrowserNeverInventsOne() {
        ForPublishingEvents events = mock(ForPublishingEvents.class);
        ContainerUpdate update = ContainerUpdate.of(MACHINE, "vaultwarden", List.of(
            container("vaultwarden", coordinates(), ContainerUpdateEligibility.UPDATABLE)));

        update.announce(new ContainerUpdate.Settlement(ContainerUpdateOutcome.RECREATE_FAILED, null), events);

        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(events).publish(eq("vpn-peers"), eq("container-update-settled"), data.capture());
        assertThat(data.getValue())
            .contains("\"outcome\":\"RECREATE_FAILED\"")
            .contains(ContainerUpdateOutcome.RECREATE_FAILED.sentence("vaultwarden"));
    }
}
