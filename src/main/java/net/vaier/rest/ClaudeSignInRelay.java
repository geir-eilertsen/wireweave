package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.CancelClaudeSignInUseCase;
import net.vaier.application.GetClaudeSignInStatusUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.OpenClaudeSignInShellUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.application.SignOutOfClaudeUseCase;
import net.vaier.application.StartClaudeSignInUseCase;
import net.vaier.application.SubmitClaudeSignInCodeUseCase;
import net.vaier.domain.ClaudeSignIn;
import net.vaier.domain.ClaudeSignInFailedException;
import net.vaier.domain.ClaudeSignInOutcome;
import net.vaier.domain.ClaudeSignInOutput;
import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.EffectiveUser;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.port.ForOpeningSshSessions.SshOutputListener;
import net.vaier.domain.port.ForOpeningSshSessions.SshSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * <b>Relays a Claude sign-in between Anthropic and one of the operator's machines.</b>
 *
 * <p>Anthropic's terms forbid a third party collecting, storing or intermediating Claude credentials or
 * session tokens — sign-in has to complete through Anthropic's own flow — while expressly permitting an
 * end user to sign the <em>unmodified</em> CLI in with their own subscription. So this class is built to
 * be clean by construction rather than by promise: Anthropic's binary runs on the target machine,
 * Anthropic's flow completes in the operator's browser and on that machine, and Vaier passes a URL out
 * and a code back in.
 *
 * <p><b>It persists nothing, and that is a hard requirement.</b> The authorization URL, the pasted code
 * and the token the CLI ends up with are transient and in-memory for the life of one sign-in, and are
 * cleared the moment it ends. There is deliberately no {@code For*} persistence port anywhere in this
 * feature, nothing reaches a {@code .yml} or the vault, and no log line here carries a URL, a code or a
 * token — machine identities and states only. If a persistence port ever appears in this file, something
 * has gone wrong.
 *
 * <p><b>A driving adapter, not a service.</b> It is driven by an HTTP request and by a clock, exactly as
 * {@link FleetCredentialDistributor} and {@link RemoteDiskWatcher} are, and it lives here rather than on
 * a service for the usual reason: it needs the <em>machine list</em> — for a machine's name, and for
 * whether Vaier can reach a shell on it — which the remote-shell domain does not own. Composing that at
 * the driving edge is how Vaier does a cross-domain read.
 *
 * <p><b>It decides nothing.</b> What the CLI's output means is {@link ClaudeSignInOutput}; what to run,
 * how long to wait, when waiting becomes abandonment and every sentence an operator reads is
 * {@link ClaudeSignIn}; which machines qualify is
 * {@link Machine#runsAShellVaierCanReach(boolean)} — the same question a fleet
 * credential asks of a machine, answered in one place rather than copied per feature.
 *
 * <p><b>Holding the CLI between two requests.</b> The operator has to leave Vaier, approve in their own
 * browser, and come back with a code, so a process must wait at a prompt across two HTTP requests. That
 * is {@link ClaudeSignIn}'s persistent shell — it supplies the PTY the CLI needs to run at all, and
 * keeps the process alive if the SSH connection drops. Vaier holds the live session in the map below and
 * nothing else.
 */
@Component
@Slf4j
public class ClaudeSignInRelay implements StartClaudeSignInUseCase, SubmitClaudeSignInCodeUseCase,
    CancelClaudeSignInUseCase, GetClaudeSignInStatusUseCase, SignOutOfClaudeUseCase {

    /**
     * How often abandoned sign-ins are swept. Scheduling mechanism, so it stays here — <em>when</em> a
     * sign-in counts as abandoned is {@link ClaudeSignIn#isAbandoned}, which is a judgement about
     * sign-ins and belongs to the domain.
     */
    private static final long SWEEP_INTERVAL_MS = 120_000;

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase hostCredentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final OpenClaudeSignInShellUseCase claudeSignInShell;
    private final Duration urlWait;
    private final Duration codeWait;

    /**
     * machine -> the sign-in currently waiting on it. In memory and nowhere else, by requirement: what a
     * waiting sign-in holds is Anthropic's URL and, for one instant, the operator's code.
     */
    private final Map<MachineId, WaitingSignIn> waiting = new ConcurrentHashMap<>();

    @Autowired
    public ClaudeSignInRelay(GetMachinesUseCase machines, GetHostCredentialUseCase hostCredentials,
                             RunRemoteCommandUseCase remoteCommand,
                             OpenClaudeSignInShellUseCase claudeSignInShell) {
        this(machines, hostCredentials, remoteCommand, claudeSignInShell, ClaudeSignIn.URL_WAIT,
            ClaudeSignIn.CODE_WAIT);
    }

    /** Same, with the waits stated — so a test can exercise the timeout without waiting out an operator's. */
    ClaudeSignInRelay(GetMachinesUseCase machines, GetHostCredentialUseCase hostCredentials,
                      RunRemoteCommandUseCase remoteCommand,
                      OpenClaudeSignInShellUseCase claudeSignInShell, Duration urlWait,
                      Duration codeWait) {
        this.machines = machines;
        this.hostCredentials = hostCredentials;
        this.remoteCommand = remoteCommand;
        this.claudeSignInShell = claudeSignInShell;
        this.urlWait = urlWait;
        this.codeWait = codeWait;
    }

    @Override
    public String startClaudeSignIn(MachineId machineId) {
        // Asked before a session is opened: whether a sign-in could accomplish anything here is the
        // domain's call, and both of its refusals save the operator a wait that would end in a timeout.
        ClaudeSignIn.requireSignInCanBegin(ClaudeSignIn.readStatus(
            remoteCommand.run(machineId, ClaudeSignIn.statusCommand()).stdout()));

        // Always start from nothing. A leftover sign-in shell is already past the point where the URL was
        // printed, and tmux's attach-or-create would silently join it — handing the operator a prompt with
        // no URL to go with it. This is also what clears a sign-in abandoned before a Vaier restart.
        discard(machineId);

        WaitingSignIn signIn = new WaitingSignIn();
        waiting.put(machineId, signIn);
        try {
            signIn.attachTo(claudeSignInShell.openClaudeSignInShell(machineId, signIn));
            String authorizationUrl = signIn.awaitAuthorizationUrl(urlWait);
            log.info("Claude sign-in started on {} — waiting for the operator's code", machineId);
            return authorizationUrl;
        } catch (RuntimeException e) {
            discard(machineId);
            throw e;
        }
    }

    @Override
    public ClaudeSignInStatus submitClaudeSignInCode(MachineId machineId, String code) {
        WaitingSignIn signIn = waiting.get(machineId);
        if (signIn == null) {
            throw ClaudeSignIn.noSignInWaiting();
        }
        // Validated before anything is touched, so a mistyped paste costs the operator a retry and not the
        // whole sign-in: the CLI is still sitting at its prompt afterwards.
        String keystrokes = ClaudeSignIn.keystrokesForCode(code);
        try {
            signIn.write(keystrokes);
            signIn.awaitOutcome(codeWait);
        } finally {
            discard(machineId);
        }
        // The CLI's own auth status is the only thing that settles this. What it printed during the
        // exchange was a hint that let the wait above end early, and nothing more.
        ClaudeSignInStatus status = statusOf(machineId);
        log.info("Claude sign-in on {} (as {}) finished as {}", machineId,
            status.effectiveUsername(), status.state());
        return status;
    }

    @Override
    public ClaudeSignInStatus signOutOfClaude(MachineId machineId) {
        // Any sign-in still waiting on this machine is now pointless, and leaving it attached to a CLI
        // that is being signed out would leave a process at a prompt nobody will ever answer.
        discard(machineId);
        remoteCommand.run(machineId, ClaudeSignIn.signOutCommand());
        // Read back rather than assumed: the logout having run is not the same as the CLI being signed
        // out, and the CLI is the only authority on that.
        ClaudeSignInStatus status = statusOf(machineId);
        log.info("Signed {} out of Claude as {} — now {}", machineId, status.effectiveUsername(),
            status.state());
        return status;
    }

    @Override
    public void cancelClaudeSignIn(MachineId machineId) {
        if (waiting.containsKey(machineId)) {
            log.info("Abandoning the Claude sign-in on {}", machineId);
        }
        discard(machineId);
    }

    @Override
    public ClaudeSignInStatus getClaudeSignInStatus(MachineId machineId) {
        return statusOf(machineOf(machineId)
            .orElseThrow(() -> new NotFoundException("No machine with id " + machineId.value())));
    }

    /**
     * One machine's standing. A machine with no shell Vaier can reach is answered without being disturbed:
     * there is nowhere a sign-in could live, so there is nothing to ask it.
     */
    private ClaudeSignInStatus statusOf(Machine machine) {
        EffectiveUser asks = effectiveUserOf(machine.id());
        if (!machine.runsAShellVaierCanReach(asks != null)) {
            return ClaudeSignInStatus.skipped(machine.id(), machine.name(), asks);
        }
        return statusOf(machine.id(), machine.name(), asks);
    }

    /**
     * Sweep sign-ins nobody came back to. The CLI waits at its prompt by design, so an operator who
     * closed the dialog leaves a process on their machine and an SSH session here. How long is too long is
     * {@link ClaudeSignIn#isAbandoned}'s call, not this sweep's.
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = SWEEP_INTERVAL_MS)
    public void endAbandonedSignIns() {
        Instant now = Instant.now();
        for (Map.Entry<MachineId, WaitingSignIn> entry : waiting.entrySet()) {
            if (entry.getValue().isAbandonedAt(now)) {
                log.info("Ending an abandoned Claude sign-in on {}", entry.getKey());
                discard(entry.getKey());
            }
        }
    }

    /**
     * Ask one machine where it stands, with the CLI's own {@code auth status}. A machine that cannot answer
     * is reported as unreachable rather than thrown: "it didn't answer" is a standing the pane can show,
     * and an exception here would replace it with an error page saying less.
     */
    private ClaudeSignInStatus statusOf(MachineId machineId, String machineName,
                                        EffectiveUser effectiveUser) {
        try {
            return ClaudeSignInStatus.read(machineId, machineName, effectiveUser,
                remoteCommand.run(machineId, ClaudeSignIn.statusCommand()).stdout());
        } catch (RuntimeException e) {
            log.debug("Could not ask {} about Claude sign-in: {}", machineId, e.toString());
            return ClaudeSignInStatus.unreachable(machineId, machineName, effectiveUser);
        }
    }

    /** The single-machine read, for the paths that act on one machine and then report it. */
    private ClaudeSignInStatus statusOf(MachineId machineId) {
        return statusOf(machineId, nameOf(machineId), effectiveUserOf(machineId));
    }

    /**
     * The user Vaier acts as on {@code machineId} — the login in its host credential — or null when Vaier
     * holds none. Every Claude answer is scoped to this user, because a sign-in lives in one user's home
     * and says nothing about any other account on the same box.
     */
    private EffectiveUser effectiveUserOf(MachineId machineId) {
        return hostCredentials.getHostCredential(machineId)
            .map(credential -> EffectiveUser.of(credential.username()))
            .orElse(null);
    }

    /**
     * End a sign-in and forget everything about it: close the held session, kill the persistent shell on
     * the machine so nothing is left waiting at a prompt, and clear the captured output — which is where
     * both the URL and the operator's echoed code live.
     */
    private void discard(MachineId machineId) {
        WaitingSignIn signIn = waiting.remove(machineId);
        if (signIn != null) {
            signIn.forget();
        }
        try {
            remoteCommand.run(machineId, ClaudeSignIn.endCommand());
        } catch (RuntimeException e) {
            // A machine that is asleep or unreachable has nothing an operator could do about it here, and
            // the shell it may still be holding costs nothing until the machine is next reachable.
            log.debug("Could not end the Claude sign-in shell on {}: {}", machineId, e.toString());
        }
    }

    /**
     * The fleet's current name for a machine, or null when it no longer has one. Deliberately not
     * {@link Machine#labelFor} — that writes prose for a person to read, and this feeds a
     * DTO whose identity field is the {@code machineId} beside it. An absent name is reported as absent
     * rather than filled in with something that would read like one.
     */
    private String nameOf(MachineId machineId) {
        return machineOf(machineId).map(Machine::name).orElse(null);
    }

    /** The fleet's entry for a machine. An in-memory read of the machine list — no SSH, no round trip. */
    private Optional<Machine> machineOf(MachineId machineId) {
        return machines.getAllMachines().stream()
            .filter(machine -> machine.id().equals(machineId))
            .findFirst();
    }

    /**
     * One sign-in in flight: the live session, and a strictly bounded window of what the CLI has printed
     * so far. Both are memory-only and both are dropped by {@link #forget()} the moment the sign-in ends.
     *
     * <p>It doubles as the {@link SshOutputListener} so the two questions worth asking of the output —
     * "has Anthropic's URL appeared?" and "has the CLI said how the code went?" — are answered as the
     * bytes arrive rather than by polling a buffer.
     *
     * <p>No {@code toString}: the capture it holds contains the URL and the operator's echoed code, and a
     * generated one is exactly how those end up in a log line nobody meant to write.
     */
    private static final class WaitingSignIn implements SshOutputListener {

        /** Enough for the CLI's banner and several full redraws of a very long URL, and no more. */
        private static final int MAX_CAPTURE = 64 * 1024;

        private final Instant startedAt = Instant.now();
        private final StringBuilder capture = new StringBuilder();
        private final CompletableFuture<String> authorizationUrl = new CompletableFuture<>();
        private final CompletableFuture<ClaudeSignInOutcome> outcome = new CompletableFuture<>();
        private volatile SshSession session;
        private volatile boolean codeWritten;

        @Override
        public void onOutput(byte[] data) {
            String text;
            synchronized (capture) {
                capture.append(new String(data, StandardCharsets.UTF_8));
                if (capture.length() > MAX_CAPTURE) {
                    capture.delete(0, capture.length() - MAX_CAPTURE);
                }
                text = capture.toString();
            }
            if (!authorizationUrl.isDone()) {
                if (ClaudeSignInOutput.reportsCliAbsent(text)) {
                    authorizationUrl.completeExceptionally(ClaudeSignIn.notInstalled());
                } else {
                    ClaudeSignInOutput.readAuthorizationUrl(text).ifPresent(authorizationUrl::complete);
                }
            }
            if (codeWritten && !outcome.isDone()) {
                ClaudeSignInOutcome read = ClaudeSignInOutput.readOutcome(text);
                if (read != ClaudeSignInOutcome.PENDING) {
                    outcome.complete(read);
                }
            }
        }

        @Override
        public void onClosed() {
            // The CLI exited. Whatever it was going to say, it has said — stop both waits now rather than
            // letting the operator sit out a timeout for output that can no longer arrive.
            authorizationUrl.completeExceptionally(ClaudeSignIn.exitedBeforeShowingUrl());
            outcome.complete(ClaudeSignInOutcome.PENDING);
        }

        void attachTo(SshSession opened) {
            this.session = opened;
        }

        boolean isAbandonedAt(Instant now) {
            return ClaudeSignIn.isAbandoned(startedAt, now);
        }

        /**
         * Anthropic's URL, or a loud, honest failure. It never returns empty and never waits forever: a
         * URL Vaier cannot find is a screen-scrape that has stopped working, and the operator needs to
         * hear that plainly along with the way round it that always works.
         */
        String awaitAuthorizationUrl(Duration wait) {
            try {
                return authorizationUrl.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw ClaudeSignIn.couldNotReadAuthorizationUrl(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ClaudeSignIn.interruptedWaitingForUrl();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof ClaudeSignInFailedException failed ? failed
                    : ClaudeSignIn.couldNotBeStarted();
            }
        }

        /**
         * Wait for the CLI to react to the code, then stop waiting. Deliberately returns nothing: what it
         * printed is a hint that lets this wait end early, and it is never the answer — the caller asks the
         * CLI's own {@code auth status} next regardless, which is the only thing that settles it.
         */
        void awaitOutcome(Duration wait) {
            try {
                outcome.get(wait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | TimeoutException | ExecutionException e) {
                // A timeout is not a failure here, and neither is a dropped session: the machine's own
                // auth status is asked next either way, and it is the only thing that settles this.
            }
        }

        /** Hand the CLI the operator's code as keystrokes. Written, not stored — not even as a field. */
        void write(String keystrokes) {
            SshSession live = session;
            if (live == null) {
                throw ClaudeSignIn.signInNotLive();
            }
            codeWritten = true;
            live.write(keystrokes.getBytes(StandardCharsets.UTF_8));
        }

        /** Drop the session and every byte of captured output. Called on every path that ends a sign-in. */
        void forget() {
            synchronized (capture) {
                capture.setLength(0);
                capture.trimToSize();
            }
            SshSession live = session;
            session = null;
            if (live != null) {
                live.close();
            }
        }
    }
}
