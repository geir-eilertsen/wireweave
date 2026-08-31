package net.vaier.domain;

import java.util.List;

/**
 * Where one {@link FleetCredential} stands on one machine: the machine's identity, its display name,
 * and the {@link FleetCredentialState} Vaier last observed there.
 *
 * <p>Keyed by {@link MachineId}, not by name: a rename must never move a credential's standing onto a
 * different machine. The name rides along purely so the browser has something to print.
 */
public record FleetCredentialStanding(MachineId machineId, String machineName,
                                      FleetCredentialState state) {

    /**
     * Whether a push actually put the credential on at least one machine. This is the line a credential
     * has to cross before the background reconcile may heal it: a push that reached nobody — every
     * machine skipped, asleep, or failed — has distributed nothing, and a healer licensed by it would be
     * pushing a secret the operator has never once seen land.
     */
    public static boolean anyLanded(List<FleetCredentialStanding> standings) {
        return standings.stream().anyMatch(s -> s.state() == FleetCredentialState.CURRENT);
    }
}
