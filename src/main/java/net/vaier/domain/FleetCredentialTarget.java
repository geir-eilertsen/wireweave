package net.vaier.domain;

/**
 * One machine considered as a place a {@link FleetCredential} could live: its identity, its display
 * name, whether the operator allows Vaier SSH there, and whether Vaier holds a login for it.
 *
 * <p>It exists so that <em>who may receive a fleet credential</em> is one domain decision rather than
 * a pair of guards re-typed at each call site. A fleet credential is a file on a filesystem behind a
 * shell — so a machine qualifies only when both halves hold, and a machine that fails either is
 * {@link FleetCredentialState#SKIPPED}, never an error: a phone has nowhere to put the file, and a
 * machine with no host credential is one Vaier is not allowed to open a session to at all.
 */
public record FleetCredentialTarget(MachineId machineId, String machineName, boolean sshAccess,
                                    boolean hasHostCredential) {

    /** Reads {@code machine} — with the answer to "does Vaier hold a login for it" — as a target. */
    public static FleetCredentialTarget of(Machine machine, boolean hasHostCredential) {
        return new FleetCredentialTarget(machine.id(), machine.name(), machine.effectiveSshAccess(),
            hasHostCredential);
    }

    /**
     * Whether Vaier can open a shell here and therefore whether a fleet credential can live here. The rule
     * itself is {@link Machine#runsAShellVaierCanReach(boolean)} — a fact about a machine, shared with
     * every other fleet-wide operation that needs a shell, so there is one copy of it rather than one per
     * feature.
     */
    public boolean runsAShellVaierCanReach() {
        return sshAccess && hasHostCredential;
    }

    /** This target's standing when it is not one Vaier may reach. */
    public FleetCredentialStanding skippedStanding() {
        return standing(FleetCredentialState.SKIPPED);
    }

    /** This target's standing in {@code state}. */
    public FleetCredentialStanding standing(FleetCredentialState state) {
        return new FleetCredentialStanding(machineId, machineName, state);
    }
}
