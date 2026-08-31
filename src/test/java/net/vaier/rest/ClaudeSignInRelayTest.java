package net.vaier.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.OpenClaudeSignInShellUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.domain.AuthMethod;
import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.ClaudeSignInFailedException;
import net.vaier.domain.ClaudeSignInOutput;
import net.vaier.domain.ClaudeSignInState;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.HostCredentialView;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.SshConnectException;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.assertj.core.groups.Tuple;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Claude sign-in relay: Vaier starts Anthropic's own CLI on a machine, relays Anthropic's
 * authorization URL out to the operator's browser, relays the code back in, and asks the machine
 * afterwards whether it ended up signed in.
 *
 * <p>Several tests here exist to hold a compliance line rather than a behaviour: Anthropic's terms
 * forbid a third party collecting, storing or intermediating Claude credentials, so the URL, the code
 * and the resulting token must never be written to disk, to a store, or to a log. Those tests are the
 * ones a future edit has to delete in order to break the rule.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeSignInRelayTest {

    private static final String URL = "https://claude.com/cai/oauth/authorize"
        + "?code=true&client_id=9d1c250a-e61b-44d9-88ed-5944d1962f5e&code_challenge=abc&state=xyz";
    private static final String CODE = "AbC123-xyz#state-9f2";
    private static final MachineId NAS = TestMachineIds.of("nas");
    private static final MachineId PHONE = TestMachineIds.of("phone");

    @Mock GetMachinesUseCase machines;
    @Mock GetHostCredentialUseCase hostCredentials;
    @Mock RunRemoteCommandUseCase remoteCommand;
    @Mock OpenClaudeSignInShellUseCase claudeSignInShell;
    @Mock SshSession session;

    private ClaudeSignInRelay relay;
    private ListAppender<ILoggingEvent> logged;
    private Logger relayLogger;

    @BeforeEach
    void setUp() {
        // Short waits: the production defaults are tens of seconds, which is right for an operator and
        // wrong for a test that deliberately never produces a URL.
        relay = new ClaudeSignInRelay(machines, hostCredentials, remoteCommand, claudeSignInShell,
            Duration.ofMillis(150), Duration.ofMillis(150));
        logged = new ListAppender<>();
        logged.start();
        relayLogger = (Logger) LoggerFactory.getLogger(ClaudeSignInRelay.class);
        relayLogger.addAppender(logged);
        relayLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        relayLogger.detachAppender(logged);
    }

    // --- Step 1 and 2: start the CLI, relay Anthropic's URL out ------------------------------------

    @Test
    void startsTheCliAndHandsBackTheAuthorizationUrlItPrinted() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL) + "Paste code here if prompted > ");

        assertThat(relay.startClaudeSignIn(NAS)).isEqualTo(URL);
        verify(claudeSignInShell).openClaudeSignInShell(eq(NAS), any());
    }

    /**
     * A sign-in shell left over from a previous attempt is already past the point where the URL was
     * printed, so attaching to it would hand the operator a spinner forever. Every start clears the
     * ground first — which is also how a sign-in abandoned before a Vaier restart gets cleaned up.
     */
    @Test
    void endsAnyLeftoverSignInShellBeforeStartingAFreshOne() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));

        relay.startClaudeSignIn(NAS);

        verify(remoteCommand).run(NAS, ClaudeSignIn.endCommand());
    }

    /**
     * Verified live on Claude Code 2.1.251: {@code claude auth login --claudeai} prints a fresh URL on an
     * already-signed-in machine and waits at its prompt, so re-signing in — onto a different account, or
     * over a credential that has gone bad — simply works. It used to be refused, back when a sign-in
     * meant running bare {@code claude} and hoping its REPL banner carried a URL.
     */
    @Test
    void letsAnAlreadySignedInMachineSignInAgain() {
        machineReports(signedIn());
        lenient().when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.endCommand()))).thenReturn(ok(""));
        lenient().when(machines.getAllMachines())
            .thenReturn(List.of(machine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER)));
        cliPrints(hyperlinked(URL));

        assertThat(relay.startClaudeSignIn(NAS)).isEqualTo(URL);
    }

    /** A plain fact, stated in the first second rather than discovered by a timeout. */
    @Test
    void refusesToStartOnAMachineWithNoClaudeInstalled() {
        machineReports(ClaudeSignInOutput.CLI_ABSENT_MARKER);

        assertThatThrownBy(() -> relay.startClaudeSignIn(NAS))
            .isInstanceOf(ClaudeSignInFailedException.class)
            .hasMessageContaining("not installed");
        verify(claudeSignInShell, never()).openClaudeSignInShell(any(), any());
    }

    /**
     * Screen-scraping a CLI Vaier does not own is the fragile part of this feature, so when it fails it
     * fails loudly and honestly: it says what Vaier could not read, that the CLI's output may have
     * changed, and what always works instead.
     */
    @Test
    void failsLoudlyWhenNoUrlEverAppearsRatherThanHangingOnASpinner() {
        machineIsSignedOut();
        cliPrints(" ⠻ Starting…\r ⠹ Starting…\r");

        assertThatThrownBy(() -> relay.startClaudeSignIn(NAS))
            .isInstanceOf(ClaudeSignInFailedException.class)
            .hasMessageContaining("could not read the login URL")
            .hasMessageContaining("output may have changed")
            .hasMessageContaining("terminal");
    }

    /** A failed start leaves nothing behind — no held SSH session, no waiting shell on the machine. */
    @Test
    void aFailedStartClosesTheSessionAndEndsTheShell() {
        machineIsSignedOut();
        cliPrints("");

        assertThatThrownBy(() -> relay.startClaudeSignIn(NAS))
            .isInstanceOf(ClaudeSignInFailedException.class);

        verify(session).close();
        verify(remoteCommand, times(2)).run(NAS, ClaudeSignIn.endCommand());
        assertThatThrownBy(() -> relay.submitClaudeSignInCode(NAS, CODE))
            .isInstanceOf(NotFoundException.class);
    }

    /** The backstop for a machine whose PATH changed between the probe and the shell opening. */
    @Test
    void stopsAtOnceWhenTheShellItselfReportsNoClaudeInstalled() {
        machineIsSignedOut();
        cliPrints("sh: " + ClaudeSignInOutput.CLI_ABSENT_MARKER + "\n");

        assertThatThrownBy(() -> relay.startClaudeSignIn(NAS))
            .isInstanceOf(ClaudeSignInFailedException.class)
            .hasMessageContaining("not installed");
    }

    // --- Step 3 and 4: relay the code back in, then report --------------------------------------------

    @Test
    void writesThePastedCodeIntoTheWaitingProcessAndReportsWhatTheMachineSays() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);
        machineReports(signedIn());

        ClaudeSignInStatus status = relay.submitClaudeSignInCode(NAS, CODE);

        ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
        verify(session).write(written.capture());
        assertThat(new String(written.getValue(), StandardCharsets.UTF_8)).isEqualTo(CODE + "\n");
        assertThat(status.state()).isEqualTo(ClaudeSignInState.SIGNED_IN);
        assertThat(status.machineName()).isEqualTo("nas");
    }

    /**
     * The report is the CLI's own {@code auth status}, not its chatter during the exchange. A
     * screen-scrape that said "Login successful" over a sign-in that never landed would be Vaier lying
     * about a machine's state.
     */
    @Test
    void reportsSignedOutWhenTheCliSaysSoWhateverItPrintedDuringTheExchange() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);
        machineReports(signedOut());

        assertThat(relay.submitClaudeSignInCode(NAS, CODE).state())
            .isEqualTo(ClaudeSignInState.SIGNED_OUT);
    }

    @Test
    void rejectsACodeWhenNoSignInIsWaitingOnThatMachine() {
        assertThatThrownBy(() -> relay.submitClaudeSignInCode(NAS, CODE))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("sign-in");
    }

    /** A code that could carry a shell command is refused before a single byte is written. */
    @Test
    void refusesAnUnsafeCodeWithoutWritingAnythingAndKeepsTheSignInAlive() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);

        assertThatThrownBy(() -> relay.submitClaudeSignInCode(NAS, "abc; rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class);

        verify(session, never()).write(any());
        // A typo must not cost the operator the whole flow — the sign-in is still there to retry.
        machineReports(signedIn());
        assertThatCode(() -> relay.submitClaudeSignInCode(NAS, CODE)).doesNotThrowAnyException();
    }

    /** One sign-in, one code. Everything it held is gone the moment it finishes. */
    @Test
    void forgetsTheSignInAsSoonAsItEnds() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);
        machineReports(signedIn());

        relay.submitClaudeSignInCode(NAS, CODE);

        verify(session).close();
        assertThatThrownBy(() -> relay.submitClaudeSignInCode(NAS, CODE))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancellingClosesTheSessionEndsTheShellAndIsSafeToRepeat() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);

        relay.cancelClaudeSignIn(NAS);
        assertThatCode(() -> relay.cancelClaudeSignIn(NAS)).doesNotThrowAnyException();

        verify(session).close();
        assertThatThrownBy(() -> relay.submitClaudeSignInCode(NAS, CODE))
            .isInstanceOf(NotFoundException.class);
    }

    // --- The compliance line ------------------------------------------------------------------------

    /**
     * Anthropic's URL and the operator's code pass through Vaier's memory and nowhere else. They are
     * never sent to a machine as part of a command, and — the failure that would be easiest to make and
     * hardest to notice — they are never written into a log line.
     */
    @Test
    void neverLogsOrShipsTheAuthorizationUrlOrTheCode() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL) + "Paste code here if prompted > ");
        relay.startClaudeSignIn(NAS);
        machineReports(signedIn());
        relay.submitClaudeSignInCode(NAS, CODE);

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(remoteCommand, atLeastOnce()).run(any(), commands.capture());
        assertThat(commands.getAllValues()).noneMatch(c -> c.contains(CODE) || c.contains(URL));

        assertThat(logged.list).isNotEmpty();
        assertThat(logged.list).noneMatch(event -> event.getFormattedMessage().contains(CODE)
            || event.getFormattedMessage().contains(URL)
            || event.getFormattedMessage().contains("oauth/authorize"));
    }

    // --- Where one machine stands -------------------------------------------------------------------

    @Test
    void reportsWhereOneMachineStandsAndWhichUserItAskedAs() {
        onlyMachine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER);
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.of(credentialView()));
        when(remoteCommand.run(NAS, ClaudeSignIn.statusCommand())).thenReturn(ok(signedIn()));

        ClaudeSignInStatus status = relay.getClaudeSignInStatus(NAS);

        assertThat(status.state()).isEqualTo(ClaudeSignInState.SIGNED_IN);
        assertThat(status.machineName()).isEqualTo("nas");
        assertThat(status.effectiveUsername()).isEqualTo("admin");
        assertThat(status.account().email()).isEqualTo("operator@example.com");
    }

    /**
     * <b>The point of having this at all.</b> Drawing one machine's pane must ask one machine. The fleet
     * read SSHes to every machine in turn, so reusing it here would put a fleet-wide sweep behind opening
     * any machine — every sleeping box waited on before a pane could paint.
     */
    @Test
    void asksOnlyTheMachineItWasAskedAbout() {
        when(machines.getAllMachines()).thenReturn(List.of(
            machine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER),
            machine("colina", MachineType.LAN_SERVER, DeviceCategory.SERVER),
            machine("nuc", MachineType.LAN_SERVER, DeviceCategory.SERVER)));
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.of(credentialView()));
        when(remoteCommand.run(NAS, ClaudeSignIn.statusCommand())).thenReturn(ok(signedIn()));

        relay.getClaudeSignInStatus(NAS);

        verify(remoteCommand, times(1)).run(any(), anyString());
        verify(remoteCommand).run(eq(NAS), anyString());
    }

    /** A machine with no login stored is not a place a sign-in could happen, and is not asked. */
    @Test
    void skipsAMachineVaierHoldsNoLoginFor() {
        onlyMachine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER);
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.empty());

        ClaudeSignInStatus status = relay.getClaudeSignInStatus(NAS);

        assertThat(status.state()).isEqualTo(ClaudeSignInState.SKIPPED);
        assertThat(status.effectiveUsername()).isNull();
        verify(remoteCommand, never()).run(any(), anyString());
    }

    /** A phone has nowhere to run a shell, so it is skipped without being asked. */
    @Test
    void skipsAMachineWithNoShellToReach() {
        onlyMachine("phone", MachineType.MOBILE_CLIENT, DeviceCategory.PHONE);
        lenient().when(hostCredentials.getHostCredential(PHONE))
            .thenReturn(Optional.of(credentialView()));

        assertThat(relay.getClaudeSignInStatus(PHONE).state()).isEqualTo(ClaudeSignInState.SKIPPED);
        verify(remoteCommand, never()).run(any(), anyString());
    }

    /** A sleeping machine is recorded as unreachable, never announced and never thrown. */
    @Test
    void marksAMachineThatDidNotAnswerAsUnreachable() {
        onlyMachine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER);
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.of(credentialView()));
        when(remoteCommand.run(eq(NAS), anyString())).thenThrow(new SshConnectException("asleep"));

        assertThat(relay.getClaudeSignInStatus(NAS).state()).isEqualTo(ClaudeSignInState.UNREACHABLE);
    }

    @Test
    void readsAMachineWithNoClaudeInstalledAsSuchRatherThanSignedOut() {
        onlyMachine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER);
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.of(credentialView()));
        when(remoteCommand.run(NAS, ClaudeSignIn.statusCommand()))
            .thenReturn(ok(ClaudeSignInOutput.CLI_ABSENT_MARKER));

        assertThat(relay.getClaudeSignInStatus(NAS).state())
            .isEqualTo(ClaudeSignInState.NOT_INSTALLED);
    }

    /**
     * An older CLI has no {@code auth} subcommand and answers with nothing usable. Unknown, never a false
     * signed-out — Vaier being unable to ask is not evidence a machine is signed out.
     */
    @Test
    void readsAnOlderCliWithNoAuthSubcommandAsUnknownRatherThanSignedOut() {
        onlyMachine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER);
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.of(credentialView()));
        when(remoteCommand.run(NAS, ClaudeSignIn.statusCommand())).thenReturn(ok(""));

        assertThat(relay.getClaudeSignInStatus(NAS).state()).isEqualTo(ClaudeSignInState.UNKNOWN);
    }

    @Test
    void refusesToReportOnAMachineTheFleetDoesNotHave() {
        when(machines.getAllMachines()).thenReturn(List.of());

        assertThatThrownBy(() -> relay.getClaudeSignInStatus(NAS))
            .isInstanceOf(NotFoundException.class);
    }

    // --- fixtures -----------------------------------------------------------------------------------

    /** The machine answers the status probe with {@code fields}; nothing else is stubbed. */
    private void machineReports(String statusJson) {
        when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.statusCommand())))
            .thenReturn(ok(statusJson));
    }

    private void machineIsSignedOut() {
        machineReports(signedOut());
        lenient().when(hostCredentials.getHostCredential(NAS))
            .thenReturn(Optional.of(credentialView()));
        lenient().when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.endCommand()))).thenReturn(ok(""));
        lenient().when(machines.getAllMachines())
            .thenReturn(List.of(machine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER)));
    }

    /**
     * The CLI writes {@code output} into the PTY the instant the shell opens. Synchronous on purpose —
     * it is the relay's own bounded wait that the timeout tests are exercising, not a race.
     */
    private void cliPrints(String output) {
        when(claudeSignInShell.openClaudeSignInShell(eq(NAS), any())).thenAnswer(invocation -> {
            SshOutputListener listener = invocation.getArgument(1);
            if (!output.isEmpty()) {
                listener.onOutput(output.getBytes(StandardCharsets.UTF_8));
            }
            return session;
        });
    }

    /** An OSC 8 hyperlink, the way the real CLI prints its authorization URL. */
    private static String hyperlinked(String url) {
        return "\033]8;;" + url + "\033\\" + url + "\033]8;;\033\\\r\n";
    }

    /** The real {@code claude auth status --json} shape, pretty-printed as the CLI emits it. */
    private static String signedIn() {
        return """
            {
              "loggedIn": true,
              "authMethod": "claude.ai",
              "email": "operator@example.com",
              "orgName": "Example Org",
              "subscriptionType": "max"
            }""";
    }

    private static String signedOut() {
        return "{\"loggedIn\": false, \"authMethod\": \"claude.ai\"}";
    }

    private static CommandResult ok(String stdout) {
        return new CommandResult(0, stdout, "", false, "fp");
    }

    private static HostCredentialView credentialView() {
        return new HostCredentialView(NAS, "admin", AuthMethod.PASSWORD, true, false);
    }

    private void onlyMachine(String name, MachineType type, DeviceCategory category) {
        when(machines.getAllMachines()).thenReturn(List.of(machine(name, type, category)));
    }

    private static Machine machine(String name, MachineType type, DeviceCategory category) {
        return new Machine(TestMachineIds.of(name), name, type, null, null, null, null, null, null,
            null, null, null, false, null, category, null);
    }

    /**
     * A machine can be deleted between a sign-in starting and finishing. The response still carries its
     * identity, so an absent name is reported absent rather than filled in with the id dressed up as one
     * — {@code Machine.labelFor}'s prose is for a person to read, not for a DTO field.
     */
    @Test
    void reportsNoNameForAMachineTheFleetNoLongerHas() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);
        machineReports(signedIn());
        when(machines.getAllMachines()).thenReturn(List.of());

        assertThat(relay.submitClaudeSignInCode(NAS, CODE).machineName()).isNull();
    }

    // --- Signing out ---------------------------------------------------------------------------------

    /**
     * Sign-out runs the CLI's own {@code auth logout}. Vaier never deletes the credential file — the
     * commands it sends are asserted here, because that is the line the whole feature is built around.
     */
    @Test
    void signsOutByRunningTheClisOwnLogoutAndNeverTouchingTheCredentialFile() {
        when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.signOutCommand()))).thenReturn(ok(""));
        machineReports(signedOut());
        when(machines.getAllMachines())
            .thenReturn(List.of(machine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER)));
        lenient().when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.endCommand()))).thenReturn(ok(""));

        assertThat(relay.signOutOfClaude(NAS).state()).isEqualTo(ClaudeSignInState.SIGNED_OUT);

        ArgumentCaptor<String> commands = ArgumentCaptor.forClass(String.class);
        verify(remoteCommand, atLeastOnce()).run(any(), commands.capture());
        assertThat(commands.getAllValues()).noneMatch(c -> c.contains(".credentials.json"));
    }

    /**
     * The standing is read back from the CLI, never assumed from the logout having run. A logout that
     * silently did nothing must not be reported as a machine that is signed out.
     */
    @Test
    void reportsTheStandingTheCliActuallyHasAfterALogout() {
        when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.signOutCommand()))).thenReturn(ok(""));
        machineReports(signedIn());
        when(machines.getAllMachines())
            .thenReturn(List.of(machine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER)));
        lenient().when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.endCommand()))).thenReturn(ok(""));

        assertThat(relay.signOutOfClaude(NAS).state()).isEqualTo(ClaudeSignInState.SIGNED_IN);
    }

    /** Signing out drops any sign-in still waiting — nobody is going to answer its prompt now. */
    @Test
    void signingOutAbandonsASignInStillWaitingOnThatMachine() {
        machineIsSignedOut();
        cliPrints(hyperlinked(URL));
        relay.startClaudeSignIn(NAS);
        when(remoteCommand.run(eq(NAS), eq(ClaudeSignIn.signOutCommand()))).thenReturn(ok(""));

        relay.signOutOfClaude(NAS);

        verify(session).close();
        assertThatThrownBy(() -> relay.submitClaudeSignInCode(NAS, CODE))
            .isInstanceOf(NotFoundException.class);
    }

    /**
     * <b>Pins the live defect.</b> Colina 27 read "signed in, max" while the operator's own work on that
     * box, running as {@code root}, was signed out — because Vaier had asked as {@code geir}, the login in
     * its host credential. The status has to carry that user, or nothing in the readout lets an operator
     * spot the mismatch.
     */
    @Test
    void everyStatusNamesTheOsUserVaierAskedAs() {
        onlyMachine("nas", MachineType.LAN_SERVER, DeviceCategory.SERVER);
        when(hostCredentials.getHostCredential(NAS)).thenReturn(Optional.of(credentialView()));
        when(remoteCommand.run(NAS, ClaudeSignIn.statusCommand())).thenReturn(ok(signedIn()));

        assertThat(relay.getClaudeSignInStatus(NAS).effectiveUsername()).isEqualTo("admin");
    }
}
