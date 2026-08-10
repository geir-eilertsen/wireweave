package net.fjordomatic.domain.port;

import net.fjordomatic.domain.MachineId;

/**
 * A driven port the domain calls to put a {@link net.fjordomatic.domain.SurvivalKit} somewhere it will still be
 * found later — on a machine in the fleet, or on the Fjord server itself.
 *
 * <p>Two methods because they are genuinely different infrastructure (one crosses the network, one does not),
 * and one port because to the domain they are the same intent: keep this file where it can be reached on a
 * day Fjord cannot be asked anything. Where on the host it lands, and how it gets there, is the adapter's
 * business — the domain decides only <em>which</em> machines and <em>whether</em> the result is good enough.
 *
 * <p>Both methods <strong>throw</strong> on failure rather than returning a status. A rollout is expected to
 * be partial — machines are off, networks drop — and the caller deliberately catches per destination so one
 * unreachable host cannot cost the fleet its other copies.
 */
public interface ForKeepingSurvivalKits {

    /**
     * Write the kit onto the machine with this identity, replacing whatever kit is already there.
     *
     * <p>By identity, not by name: a rollout takes minutes across a fleet of sleeping machines, and a machine
     * renamed inside that window would otherwise resolve to nothing — or, worse, to whichever machine had
     * since taken the old name. What went wrong is reported by the caller, which holds the name to say it in.
     */
    void keepOn(MachineId machineId, String content);

    /**
     * Write the kit on the Fjord server itself. Never one of the fleet copies — a kit that dies with Fjord is
     * not redundancy — but the copy that is there on the far likelier day when Fjord will not start and its
     * disk is fine.
     */
    void keepOnTheFjordServer(String content);
}
