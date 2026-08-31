package net.vaier.rest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DistributeFleetCredentialUseCase;
import net.vaier.application.GetFleetCredentialStandingsUseCase;
import net.vaier.application.GetHostCredentialUseCase;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.application.WithdrawFleetCredentialUseCase;
import net.vaier.domain.CommandResult;
import net.vaier.domain.FleetCredential;
import net.vaier.domain.FleetCredentialStanding;
import net.vaier.domain.FleetCredentialState;
import net.vaier.domain.FleetCredentialTarget;
import net.vaier.domain.Machine;
import net.vaier.domain.NotFoundException;
import net.vaier.domain.port.ForPersistingFleetCredentials;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * <b>Puts a fleet credential on the fleet, and takes it off again.</b> A driving adapter, not a service:
 * it is driven by an HTTP request and by a clock, exactly as a controller and
 * {@link RemoteDiskWatcher} are, and it exists here rather than on {@code TerminalService} for one
 * reason — distributing needs the <em>machine list</em>, which the credential vault's domain does not
 * own. Composing that at the driving edge is how Vaier does a cross-domain read: it fans
 * {@link GetMachinesUseCase}, {@link GetHostCredentialUseCase} and {@link RunRemoteCommandUseCase}
 * together the way {@code RemoteDiskWatcher} already does, rather than teaching one service about
 * another's data.
 *
 * <p>It decides nothing. Which machines qualify is {@link FleetCredentialTarget}; what to send is
 * {@link FleetCredential}'s rendered shell; what came back is that entity's reading of the report; and
 * whether a push has landed well enough to license the background sweep is
 * {@link FleetCredentialStanding#anyLanded}.
 *
 * <p><b>The reconcile is deliberately timid.</b> It only ever heals — writes where the credential is
 * missing or has drifted — and only for a credential the operator has already distributed by hand at
 * least once. A credential that has never been pushed is never pushed by a timer, a withdrawn one is
 * never healed back (withdrawing stands it down), and an unreachable machine is skipped in silence:
 * machines sleep, and a fleet-wide secret sweep is the last thing that should be emailing anybody about
 * it. It also never stands a credential <em>up</em> — only an operator's own distribute does that.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FleetCredentialDistributor implements DistributeFleetCredentialUseCase,
    WithdrawFleetCredentialUseCase, GetFleetCredentialStandingsUseCase {

    private final GetMachinesUseCase machines;
    private final GetHostCredentialUseCase hostCredentials;
    private final RunRemoteCommandUseCase remoteCommand;
    private final ForPersistingFleetCredentials fleetCredentials;

    /**
     * How often the fleet is reconciled. The same five-minute cadence as the disk sweep, offset so the
     * two rounds do not knock on every door at the same instant.
     */
    private static final long RECONCILE_INTERVAL_MS = 300_000;
    private static final long RECONCILE_INITIAL_DELAY_MS = 120_000;

    /**
     * credential name -> where it last stood on each machine. An <em>observation</em>, which is why it
     * lives in memory: nothing is suppressed on the strength of it, no alert hangs off it, and the next
     * reconcile refreshes it within five minutes. That is the opposite of the disk-pressure latch, whose
     * state had to move to disk precisely because a redeploy wiping it silenced real alerts.
     */
    private final Map<String, List<FleetCredentialStanding>> standings = new ConcurrentHashMap<>();

    @Override
    public List<FleetCredentialStanding> distributeFleetCredential(String name) {
        FleetCredential credential = require(name);
        List<FleetCredentialStanding> result =
            sweep(credential, credential.writeCommand(), credential::readWriteOutcome);
        if (FleetCredentialStanding.anyLanded(result)) {
            fleetCredentials.save(credential.markDistributed());
        }
        log.info("Distributed fleet credential {}: {}", LogSafe.forLog(name), summarize(result));
        return result;
    }

    @Override
    public List<FleetCredentialStanding> withdrawFleetCredential(String name) {
        FleetCredential credential = require(name);
        List<FleetCredentialStanding> result =
            sweep(credential, credential.removeCommand(), credential::readWithdrawal);
        // Stood down whatever the fleet said: the operator has revoked it, so the healer must stop even
        // if a machine was asleep and still holds a copy. The standings say which those are.
        fleetCredentials.save(credential.markWithdrawn());
        log.info("Withdrew fleet credential {}: {}", LogSafe.forLog(name), summarize(result));
        return result;
    }

    @Override
    public List<FleetCredentialStanding> getFleetCredentialStandings(String name) {
        return standings.getOrDefault(name, List.of());
    }

    /**
     * The Stage 2 sweep: for each credential the operator has already distributed, ask every reachable
     * machine what it holds and write only where there is a hole. The digests are compared, never the
     * secrets — the machine reports a SHA-256 and Vaier compares it to its own, so a check costs no
     * secret bytes on the wire.
     */
    @Scheduled(fixedDelay = RECONCILE_INTERVAL_MS, initialDelay = RECONCILE_INITIAL_DELAY_MS)
    public void reconcileFleetCredentials() {
        for (FleetCredential credential : fleetCredentials.getAll()) {
            if (!credential.shouldReconcile()) {
                continue;
            }
            try {
                heal(credential);
            } catch (Exception e) {
                // One credential's bad day must never cost the others theirs.
                log.warn("Could not reconcile fleet credential {}: {}",
                    LogSafe.forLog(credential.name()), e.toString());
            }
        }
    }

    private void heal(FleetCredential credential) {
        List<FleetCredentialStanding> result = new ArrayList<>();
        for (FleetCredentialTarget target : targets()) {
            if (!target.runsAShellVaierCanReach()) {
                result.add(target.skippedStanding());
                continue;
            }
            result.add(healOne(credential, target));
        }
        standings.put(credential.name(), List.copyOf(result));
    }

    private FleetCredentialStanding healOne(FleetCredential credential, FleetCredentialTarget target) {
        try {
            FleetCredentialState observed = credential.readVerification(
                remoteCommand.run(target.machineId(), credential.verifyCommand()).stdout());
            if (!observed.needsHealing()) {
                return target.standing(observed);
            }
            log.info("Healing fleet credential {} on {} (was {})", LogSafe.forLog(credential.name()),
                target.machineId(), observed);
            return target.standing(credential.readWriteOutcome(
                remoteCommand.run(target.machineId(), credential.writeCommand()).stdout()));
        } catch (RuntimeException e) {
            // A machine that is asleep, moved, or refusing is not trouble an operator can act on, so it
            // is recorded and never announced.
            log.debug("Skipping {} while reconciling fleet credential {}: {}",
                target.machineId(), LogSafe.forLog(credential.name()), e.toString());
            return target.standing(FleetCredentialState.UNREACHABLE);
        }
    }

    /**
     * Run {@code command} on every machine that runs a shell Vaier can reach, read each result with
     * {@code readOutcome}, and remember the standings. One machine's failure never stops the sweep.
     */
    private List<FleetCredentialStanding> sweep(FleetCredential credential, String command,
                                                Function<String, FleetCredentialState> readOutcome) {
        List<FleetCredentialStanding> result = new ArrayList<>();
        for (FleetCredentialTarget target : targets()) {
            if (!target.runsAShellVaierCanReach()) {
                result.add(target.skippedStanding());
                continue;
            }
            result.add(runOn(target, command, readOutcome));
        }
        List<FleetCredentialStanding> settled = List.copyOf(result);
        standings.put(credential.name(), settled);
        return settled;
    }

    private FleetCredentialStanding runOn(FleetCredentialTarget target, String command,
                                          Function<String, FleetCredentialState> readOutcome) {
        try {
            CommandResult run = remoteCommand.run(target.machineId(), command);
            return target.standing(readOutcome.apply(run.stdout()));
        } catch (RuntimeException e) {
            log.debug("Could not reach {} for a fleet credential: {}", target.machineId(), e.toString());
            return target.standing(FleetCredentialState.UNREACHABLE);
        }
    }

    /** Every machine in the fleet, read as a place a fleet credential could live. */
    private List<FleetCredentialTarget> targets() {
        List<FleetCredentialTarget> targets = new ArrayList<>();
        for (Machine machine : machines.getAllMachines()) {
            targets.add(FleetCredentialTarget.of(machine,
                hostCredentials.getHostCredential(machine.id()).isPresent()));
        }
        return targets;
    }

    private FleetCredential require(String name) {
        return fleetCredentials.getByName(name).orElseThrow(
            () -> new NotFoundException("No fleet credential named " + name));
    }

    /** A count per state — never a machine's secret, and never the credential's content. */
    private static String summarize(List<FleetCredentialStanding> standings) {
        Map<FleetCredentialState, Long> counts = new EnumMap<>(FleetCredentialState.class);
        for (FleetCredentialStanding standing : standings) {
            counts.merge(standing.state(), 1L, Long::sum);
        }
        return counts.toString();
    }
}
