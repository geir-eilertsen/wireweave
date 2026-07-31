package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure-domain assembler for a machine's progressive-adoption nudges. It composes the applicable
 * {@link MachineNudge}s from the per-kind factories — each factory owns the "should this fire?"
 * decision from already-cached signals, and returns {@link java.util.Optional#empty()} when its
 * nudge does not apply. This class holds no decision of its own; it only gathers and orders.
 *
 * <p>The signals are gathered by the driving edge (the machines controller composes them from the
 * relevant {@code *UseCase}s) and handed in here — so no application service reaches across domains
 * to collect nudges, and no service implements a driven port to expose them. See CLAUDE.md,
 * "Cross-domain reads are different from cross-domain writes".
 */
public final class MachineNudges {

    private MachineNudges() {
    }

    /**
     * The nudges that apply to {@code machine}, in a stable order (publish, back-up, designate-backup-server,
     * back-up-as-root). Each is included only when its factory says so.
     *
     * <p>The machine's backup job arrives as the job itself rather than as a pre-computed
     * "already protected" flag: whether a machine is protected <em>is</em> whether it has a job, and that is
     * a domain reading, not arithmetic for a controller to do on the way in. The same job then answers the
     * back-up-as-root question, so the driving edge fetches it once and decides nothing.
     *
     * @param publishableCount services exposed on the machine but not yet routed through Vaier
     * @param reachable        whether the machine is reachable right now (from cached signals)
     * @param hasCredential    whether Vaier already holds an SSH credential for the machine
     * @param job              the machine's backup job, or empty when nothing on it is backed up
     * @param latestRun        the machine's most recent backup run, or empty when it has never run
     * @param fleet            the fleet's backup-server posture (drives the designate nudge)
     */
    public static List<MachineNudge> forMachine(Machine machine, int publishableCount,
                                                boolean reachable, boolean hasCredential,
                                                Optional<BackupJob> job, Optional<BackupRun> latestRun,
                                                BackupFleet fleet) {
        List<MachineNudge> nudges = new ArrayList<>();
        MachineNudge.publish(machine.name(), publishableCount).ifPresent(nudges::add);
        MachineNudge.backUp(machine.name(), reachable, hasCredential, job.isPresent()).ifPresent(nudges::add);
        MachineNudge.designateBackupServer(machine, fleet).ifPresent(nudges::add);
        MachineNudge.backUpAsRoot(machine.name(), latestRun, job).ifPresent(nudges::add);
        return List.copyOf(nudges);
    }
}
