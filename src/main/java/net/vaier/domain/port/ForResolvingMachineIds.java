package net.vaier.domain.port;

import net.vaier.domain.MachineId;

import java.util.Optional;

/**
 * Driven port turning a machine's display <em>name</em> into its {@link MachineId} — the one place
 * Vaier crosses from what an operator typed to what a machine actually is.
 *
 * <p>It exists so that translation happens exactly once. Vaier's REST paths, its terminal panes and its
 * Explorer coordinates all still carry names, while the stores behind them are keyed by identity; a
 * second copy of this lookup would be a second chance for the two to disagree about which machine is
 * meant. Consumers that already hold a {@link MachineId} never come here.
 *
 * <p>Resolution spans every machine kind — VPN peers, LAN servers, and the Vaier server itself — and
 * is empty when no machine bears the name. Comparison follows the same rule as the uniqueness guard
 * that makes names safe to look up at all: case-insensitive, ignoring surrounding whitespace.
 *
 * <p>It crosses back the other way too, for the callers that hold an identity and need something to say to
 * a person or to hand to the still-name-keyed SSH path. Both directions are the same lookup over the same
 * three stores, so they cannot disagree about which machine is which.
 */
public interface ForResolvingMachineIds {

    /** The id of the machine named {@code machineName}, or empty when no machine bears that name. */
    Optional<MachineId> idForName(String machineName);

    /**
     * The display name of the machine {@code machineId}, or empty when no machine has that id — which is
     * what a record pointing at a decommissioned machine looks like, and is a fact worth being able to see
     * rather than a name worth inventing.
     *
     * <p>This direction exists for the <em>driven</em> side — an adapter holding an identity that cannot
     * reach a {@code *UseCase}. A controller or a rest-layer orchestrator composes {@code GetMachinesUseCase}
     * at the driving edge instead, which is what the architecture prescribes for a cross-domain read; that
     * is a deliberate split, not a seam that was forgotten. Both sides end at the same three stores.
     */
    Optional<String> nameForId(MachineId machineId);
}
