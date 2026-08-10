package net.fjordomatic.domain;

import lombok.Builder;

import java.util.Optional;

/**
 * A single evidence-backed, yes/no suggestion Fjord surfaces on a reachable machine — a
 * progressive-adoption nudge. Each nudge asks the operator to adopt one more of Fjord's capabilities
 * for a machine Fjord already knows about, and carries everything the operator needs to make the call
 * without leaving the machine: a {@link #kind}, an operator-facing {@link #title}, the {@link #evidence}
 * (the "why" Fjord is suggesting it, drawn from already-cached state), and an {@link #action} hint
 * describing what happens on "yes".
 *
 * <p>Pure domain: the "should we suggest X?" decision for each kind is a static factory here, composed
 * from domain predicates ({@link BackupFleet#needsBackupServer()}, {@link DeviceCategory#isStorageClass()},
 * a machine's reachability, whether it is already protected). A factory returns {@link Optional#empty()}
 * when the nudge does not apply, so an emitter never has to re-decide in the application layer.
 *
 * <p>{@code value} is the <b>datum the operator is saying yes to</b>, for the nudges whose action needs
 * one — today only {@link Kind#ROUTE_LAN}, which carries the detected CIDR. It is null for every nudge
 * whose action needs no argument. It exists so a browser never has to recover a value by reading it back
 * out of the title it was rendered into.
 */
@Builder
public record MachineNudge(String machineName, Kind kind, String title, String evidence, String action,
                           String value) {

    /** The kinds of nudge Fjord can raise on a machine. */
    public enum Kind {
        /** The machine exposes services that are not yet routed through Fjord. */
        PUBLISH,
        /** The machine is a reachable, credentialed host with nothing backed up. */
        BACK_UP,
        /** The fleet has no backup server yet and this machine could host one. */
        DESIGNATE_BACKUP_SERVER,
        /** The machine's last backup lost files to permissions, and reading them as root would get them. */
        BACK_UP_AS_ROOT,
        /** Fjord read a network off the machine that nothing else in the fleet can reach yet. */
        ROUTE_LAN
    }

    /**
     * A nudge whose action needs no argument — the four original kinds, where saying yes opens a pane or
     * flips a flag Fjord already knows the value of.
     */
    public MachineNudge(String machineName, Kind kind, String title, String evidence, String action) {
        this(machineName, kind, title, evidence, action, null);
    }

    public MachineNudge {
        if (machineName == null || machineName.isBlank()) {
            throw new IllegalArgumentException("MachineNudge machineName must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("MachineNudge kind must not be null");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("MachineNudge title must not be blank");
        }
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("MachineNudge evidence must not be blank");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("MachineNudge action must not be blank");
        }
    }

    /**
     * PUBLISH — suggest routing the machine's exposed services through Fjord when it has at least one
     * publishable service. The evidence names how many are exposed but unrouted; no nudge when none are.
     */
    public static Optional<MachineNudge> publish(String machineName, int publishableCount) {
        if (publishableCount <= 0) {
            return Optional.empty();
        }
        String plural = publishableCount == 1 ? "" : "s";
        return Optional.of(new MachineNudge(machineName, Kind.PUBLISH,
            "Publish " + publishableCount + " service" + plural,
            publishableCount + " service" + plural + " exposed on this machine, none routed through Fjord yet",
            "Give each an HTTPS address and a launchpad tile"));
    }

    /**
     * BACK_UP — suggest protecting the machine when it is reachable, Fjord already holds an SSH
     * credential for it, and nothing on it is protected yet. Missing any of the three ⇒ no nudge.
     * Borg readiness is deliberately not consulted here — the protect-paths flow handles it at
     * action time — so the decision rests only on cheap, already-cached signals.
     */
    public static Optional<MachineNudge> backUp(String machineName, boolean reachable,
                                                boolean hasCredential, boolean alreadyProtected) {
        if (!reachable || !hasCredential || alreadyProtected) {
            return Optional.empty();
        }
        return Optional.of(new MachineNudge(machineName, Kind.BACK_UP,
            "Back up " + machineName,
            "Reachable, Fjord holds an SSH credential, and nothing on it is backed up yet",
            "Pick folders to protect on a schedule"));
    }

    /**
     * DESIGNATE_BACKUP_SERVER — suggest making this machine the fleet's backup server, but only when the
     * fleet {@link BackupFleet#needsBackupServer() has none yet} and the machine is
     * {@link DeviceCategory#isStorageClass() storage-class} (a NAS, or a general server). Once a backup
     * server exists the fleet no longer needs one, so this returns empty.
     */
    public static Optional<MachineNudge> designateBackupServer(Machine machine, BackupFleet fleet) {
        if (!fleet.needsBackupServer() || !machine.deviceCategory().isStorageClass()) {
            return Optional.empty();
        }
        return Optional.of(new MachineNudge(machine.name(), Kind.DESIGNATE_BACKUP_SERVER,
            "Make " + machine.name() + " the backup server",
            "The fleet has no backup server yet, and this machine has storage to host one",
            "Set up borg on it so other machines can back up here"));
    }

    /**
     * BACK_UP_AS_ROOT (#334) — suggest {@link BackupJob#backupAsRoot() backing up as root} once, and only
     * once, Fjord has the evidence that not doing so is costing the operator data: the machine's latest
     * {@link BackupRun} settled {@link BackupRunStatus#INCOMPLETE} and its {@link UnreadableFiles} name files
     * borg was denied on. The archive has holes, and the files are the proof.
     *
     * <p>This exists because the same question used to be asked <em>in advance</em>, as a checkbox, of an
     * operator with nothing to reason from — file ownership inside container volumes, and the security
     * envelope of a sudoers rule. Here it is one decision at the moment it means something, so the three
     * conditions are exactly the three that make it meaningful:
     * <ul>
     *   <li>there is a job — the nudge asks a job to change, so there must be one to change;</li>
     *   <li>the job is <b>not</b> already backing up as root — root already reads everything, so whatever it
     *       could not read, this nudge cannot fix;</li>
     *   <li>the latest run lost files <b>to permissions</b>. Keying on {@link UnreadableFiles#any()} rather
     *       than on the status word alone is what keeps an incomplete run that lost data some other way — or
     *       a run that merely grumbled — from raising a suggestion that would not help it.</li>
     * </ul>
     * There is deliberately no "Fjord is already root here" guard: a root login is never denied, so it
     * produces no denial line, so {@code any()} is false and this cannot fire there anyway.
     */
    public static Optional<MachineNudge> backUpAsRoot(String machineName, Optional<BackupRun> latestRun,
                                                      Optional<BackupJob> job) {
        if (job.isEmpty() || job.get().backupAsRoot() || latestRun.isEmpty()) {
            return Optional.empty();
        }
        BackupRun run = latestRun.get();
        UnreadableFiles lost = run.unreadableFiles();
        if (run.status() != BackupRunStatus.INCOMPLETE || !lost.any()) {
            return Optional.empty();
        }
        String plural = lost.total() == 1 ? "" : "s";
        return Optional.of(new MachineNudge(machineName, Kind.BACK_UP_AS_ROOT,
            "This backup is missing " + lost.total() + " file" + plural,
            lost.inOneLine(),
            "Fjord will read every file here, whoever owns them"));
    }

    /**
     * ROUTE_LAN (#333) — offer the network Fjord <b>read off the machine itself</b>, so the operator is
     * never asked to type a CIDR. Until this existed, making a house reachable meant knowing CIDR
     * notation, knowing which subnet the router hands out, and knowing that getting it wrong either does
     * nothing or severs a host's uplink. Fjord already holds an SSH credential for the machine and already
     * runs {@code df} over that connection, so it reads {@link MachineNetworks the answer} on the same
     * sweep and asks only the question that is genuinely the operator's: <em>should the fleet reach that
     * network?</em>
     *
     * <p>Four conditions, each closing off a way the suggestion could be wrong:
     * <ul>
     *   <li><b>the machine could relay at all</b> — a VPN peer of a server type. A LAN server has no
     *       tunnel to route into, and a personal device is not somebody's gateway;</li>
     *   <li><b>nothing is routed for it yet</b>. A machine with a {@code lanCidr} has already had this
     *       question answered, and a nudge is a question, not a correction;</li>
     *   <li><b>Fjord actually read a network</b>. {@link MachineNetworks#lanCandidate()} is empty when the
     *       machine could not be read, or when its default route leaves by a tunnel — and empty means say
     *       nothing. There is deliberately no fallback guess from the machine's LAN address: a guessed
     *       {@code /24} is a plausible-looking wrong answer, which is worse than no answer;</li>
     *   <li><b>it would not blackhole the host that installs the route</b> —
     *       {@link UplinkGuard#wouldBlackhole}. Accepting adds {@code ip route <cidr> dev wg0} on the Fjord
     *       server, so the address to judge against is the Fjord server's uplink, not this machine's: a
     *       relay is never at risk from its own LAN, because its own LAN is not routed into its own
     *       tunnel. This is also why the Fjord server can never be offered its own network — its detected
     *       LAN contains its own uplink address by construction.</li>
     * </ul>
     *
     * @param machine              the machine being nudged
     * @param detected             what Fjord last read off that machine
     * @param routingHostNetworks  what Fjord last read off the host that would install the route (the
     *                             Fjord server); {@link MachineNetworks#unknown()} when it has never been
     *                             read, which proves nothing and so refuses nothing
     */
    public static Optional<MachineNudge> routeLan(Machine machine, MachineNetworks detected,
                                                  MachineNetworks routingHostNetworks) {
        if (!machine.canRelayALan()) {
            return Optional.empty();
        }
        if (machine.lanCidr() != null && !machine.lanCidr().isBlank()) {
            return Optional.empty();
        }
        Optional<MachineNetworks.Network> candidate = detected.lanCandidate();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        String cidr = candidate.get().cidr();
        if (routingHostNetworks.uplinkAddress()
                .map(uplink -> UplinkGuard.wouldBlackhole(cidr, uplink))
                .orElse(false)) {
            return Optional.empty();
        }
        return Optional.of(MachineNudge.builder()
            .machineName(machine.name())
            .kind(Kind.ROUTE_LAN)
            .title(machine.name() + " sits on " + cidr)
            .evidence("Read from the machine itself, on its " + candidate.get().interfaceName()
                + " interface — nothing else in your fleet can reach that network yet")
            .action("Route that network through this machine, so the fleet can reach what is on it")
            .value(cidr)
            .build());
    }
}
