package net.fjordomatic.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fjordomatic.domain.port.ForKeepingSurvivalKits;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Putting the kit where it was decided to go.
 *
 * <p>The rule that matters here is that one unreachable machine must not cost the fleet its other copies. A
 * rollout that stopped at the first failure would leave the fleet with fewer kits than it thinks it has —
 * which is the same silent staleness this whole feature exists to prevent, arriving by a different door.
 */
class SurvivalKitRolloutTest {

    private static final String KIT = "VAIER SURVIVAL KIT\n-----BEGIN VAIER SURVIVAL KIT-----\nabc\n";

    /** Records what was written where, and fails for whichever machines it is told to. */
    private static class Keeper implements ForKeepingSurvivalKits {
        final Map<MachineId, String> written = new ConcurrentHashMap<>();
        final List<MachineId> failing = new ArrayList<>();
        String localCopy;
        boolean localFails;

        @Override
        public void keepOn(MachineId machineId, String content) {
            if (failing.contains(machineId)) {
                throw new IllegalStateException("ssh: connection reset");
            }
            written.put(machineId, content);
        }

        @Override
        public void keepOnTheFjordServer(String content) {
            if (localFails) {
                throw new IllegalStateException("disk full");
            }
            localCopy = content;
        }
    }

    /**
     * Machines are addressed by identity, and named only for the report — a rename mid-rollout must not
     * strand a copy, and a failure an operator has to act on must not read as a UUID.
     */
    private static final Map<String, MachineId> IDS = new ConcurrentHashMap<>();

    private static MachineId idOf(String machineName) {
        return IDS.computeIfAbsent(machineName, n -> MachineId.generate());
    }

    private SurvivalKitHosts.Selection selection(String... machines) {
        return new SurvivalKitHosts.Selection(
            List.of(machines).stream()
                .map(m -> new SurvivalKitHosts.Placement(idOf(m), m, m, "chosen")).toList(),
            List.of());
    }

    @Test
    void everyChosenMachineGetsTheKit_andSoDoesTheFjordServer() {
        Keeper keeper = new Keeper();

        SurvivalKitRollout.Result result = SurvivalKitRollout.distribute(selection("Apalveien 5", "Colina-27"),
            KIT, keeper);

        assertThat(keeper.written).containsOnlyKeys(idOf("Apalveien 5"), idOf("Colina-27"));
        assertThat(keeper.written.values()).allMatch(KIT::equals);
        assertThat(keeper.localCopy).isEqualTo(KIT);
        assertThat(result.failures()).isEmpty();
    }

    @Test
    void oneUnreachableMachineDoesNotCostTheFleetItsOtherCopies() {
        // Stopping at the first failure would leave the fleet holding fewer kits than it believes it has —
        // the same silent staleness the whole feature exists against, arriving by another door.
        Keeper keeper = new Keeper();
        keeper.failing.add(idOf("Apalveien 5"));

        SurvivalKitRollout.Result result = SurvivalKitRollout.distribute(
            selection("Apalveien 5", "Colina-27"), KIT, keeper);

        assertThat(keeper.written).containsOnlyKeys(idOf("Colina-27"));
        assertThat(keeper.localCopy).isEqualTo(KIT);
        assertThat(result.failures()).hasSize(1);
        // Named, not identified: this is a line an operator has to act on.
        assertThat(result.failures().get(0).machineName()).isEqualTo("Apalveien 5");
        assertThat(result.failures().get(0).reason()).contains("connection reset");
    }

    @Test
    void aFailedLocalCopyIsReportedLikeAnyOther_notSwallowedBecauseItIsNearby() {
        Keeper keeper = new Keeper();
        keeper.localFails = true;

        SurvivalKitRollout.Result result = SurvivalKitRollout.distribute(selection("Colina-27"), KIT, keeper);

        assertThat(keeper.written).containsOnlyKeys(idOf("Colina-27"));
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).reason()).contains("disk full");
    }

    @Test
    void aRolloutThatReachedNoRemoteMachineAtAllSaysSo_becauseTheLocalCopyAloneIsNotAKit() {
        // A copy that dies with Fjord is not redundancy. If every remote write failed, the fleet is back to
        // the circle the kit exists to break, and that must not read as a partial success.
        Keeper keeper = new Keeper();
        keeper.failing.add(idOf("Apalveien 5"));
        keeper.failing.add(idOf("Colina-27"));

        SurvivalKitRollout.Result result = SurvivalKitRollout.distribute(
            selection("Apalveien 5", "Colina-27"), KIT, keeper);

        assertThat(result.survivesLossOfFjord()).isFalse();
        assertThat(result.copiesKept()).isZero();
    }

    /**
     * The verdict a sweep records against: only a rollout that reached everything counts as written, so a
     * host that was asleep is tried again rather than being quietly assumed to hold a kit.
     */
    @Test
    void reachedEveryDestination_isFalseWhileAnyDestinationIsMissingItsCopy() {
        Keeper keeper = new Keeper();
        keeper.failing.add(idOf("Apalveien 5"));

        SurvivalKitRollout.Result partial = SurvivalKitRollout.distribute(
            selection("Apalveien 5", "Colina-27"), KIT, keeper);
        assertThat(partial.reachedEveryDestination()).isFalse();

        keeper.failing.clear();
        SurvivalKitRollout.Result complete = SurvivalKitRollout.distribute(
            selection("Apalveien 5", "Colina-27"), KIT, keeper);
        assertThat(complete.reachedEveryDestination()).isTrue();
    }

    @Test
    void aRolloutWithNoChosenMachineNeverSurvivesFjord_howeverWellTheLocalCopyWent() {
        Keeper keeper = new Keeper();

        SurvivalKitRollout.Result result = SurvivalKitRollout.distribute(selection(), KIT, keeper);

        assertThat(keeper.written).isEmpty();
        assertThat(keeper.localCopy).isEqualTo(KIT);
        assertThat(result.survivesLossOfFjord()).isFalse();
    }
}
