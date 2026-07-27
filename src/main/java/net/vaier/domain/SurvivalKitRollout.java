package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import net.vaier.domain.port.ForKeepingSurvivalKits;

/**
 * Putting the kit where {@link SurvivalKitHosts} decided it should go.
 *
 * <p>Two decisions live here, and both are about what counts as having succeeded.
 *
 * <p><b>A failure never stops the rollout.</b> Machines are off, networks drop, a host is mid-reboot. If the
 * first unreachable machine aborted the run, the fleet would end up holding fewer kits than it believes it
 * has — which is the same silent staleness this whole feature exists to prevent, arriving by a different
 * door. Every destination is attempted; the ones that failed are named.
 *
 * <p><b>The local copy never counts.</b> The Vaier server gets a kit too, because the likeliest failure by
 * far is a Vaier that will not start on a host whose disk is fine. But it dies with the machine it is meant
 * to outlive, so a rollout that reached no fleet machine has not produced anything that survives Vaier,
 * however well the local write went, and must not read as a partial success.
 */
public final class SurvivalKitRollout {

    private SurvivalKitRollout() {}

    /** One destination that would not take the kit, and what it said. */
    public record Failure(String machineName, String reason) {}

    /**
     * What a rollout achieved: how many fleet machines now hold this kit, and everything that refused it.
     *
     * @param copiesKept fleet machines only — the Vaier server's own copy is never counted
     */
    public record Result(int copiesKept, List<Failure> failures) {

        /**
         * Whether this rollout produced anything that outlives the Vaier server. False when no fleet machine
         * took a copy, at which point the fleet is back inside the circle the kit exists to break — worth
         * distinguishing sharply from "some copies failed", because the two need different alarm.
         */
        public boolean survivesLossOfVaier() {
            return copiesKept > 0;
        }
    }

    /** Write {@code kit} to every chosen machine and to the Vaier server, attempting all of them. */
    public static Result distribute(SurvivalKitHosts.Selection selection, String kit,
                                    ForKeepingSurvivalKits keeper) {
        List<Failure> failures = new ArrayList<>();
        int kept = 0;

        for (SurvivalKitHosts.Placement placement : selection.chosen()) {
            try {
                keeper.keepOn(placement.machineId(), kit);
                kept++;
            } catch (RuntimeException e) {
                failures.add(new Failure(placement.machineName(), describe(e)));
            }
        }

        try {
            keeper.keepOnTheVaierServer(kit);
        } catch (RuntimeException e) {
            // Reported like any other destination rather than swallowed for being nearby: a local copy that
            // silently stopped being written is a stale kit sitting exactly where someone would look first.
            failures.add(new Failure("the Vaier server", describe(e)));
        }
        return new Result(kept, List.copyOf(failures));
    }

    /** What went wrong, in the words the machine used; never a stack trace and never a secret. */
    private static String describe(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
