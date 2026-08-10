package net.vaier.domain;

import net.vaier.domain.port.ForHoldingMachineDiskStandings;
import net.vaier.domain.port.ForPublishingEvents;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>machine disk standing</b>: the worst thing true about one machine's watched filesystems right now,
 * so the Explorer's fleet listing can say it on the card without asking the machine anything.
 *
 * <p>The decisions pinned here are the ones a card would otherwise be free to invent: which filesystem is
 * "the worst" when a machine has several, that a <b>muted filesystem</b> can never make a machine look like
 * it is in trouble, and that a machine with nothing to judge has no standing at all rather than a healthy
 * one.
 */
class MachineDiskStandingTest {

    private static final MachineId NAS = TestMachineIds.of("NAS");
    private static final int GLOBAL_THRESHOLD = 85;

    /** A filesystem at {@code usedPercent}, sized so the numbers are plausible. */
    private static RemoteDiskUsage fs(String mountPoint, int usedPercent) {
        return new RemoteDiskUsage("NAS", "/dev/sda1", mountPoint, 1000, usedPercent * 10L,
            (100 - usedPercent) * 10L, usedPercent);
    }

    private static DiskWatches watches(DiskWatch... entries) {
        return new DiskWatches(List.of(entries));
    }

    private static MachineDiskStanding standingOf(List<RemoteDiskUsage> filesystems, DiskWatches watches) {
        return MachineDiskStanding.of(NAS, filesystems, watches, GLOBAL_THRESHOLD).orElseThrow();
    }

    @Test
    void theWorstFilesystem_isTheOneClosestToItsOwnThreshold_notTheFullestOne() {
        // /boot is fuller, but it has a threshold of its own that it is nowhere near; /volume1 is the disk
        // about to cause trouble. "Worst" is headroom against the threshold Vaier actually judges by, never
        // the raw percentage — otherwise a partition that is full by design would speak for every machine.
        MachineDiskStanding standing = standingOf(
            List.of(fs("/boot", 92), fs("/volume1", 84)),
            watches(new DiskWatch(NAS, "/boot", true, 99)));

        assertThat(standing.worstMountPoint()).isEqualTo("/volume1");
        assertThat(standing.worstUsedPercent()).isEqualTo(84);
        assertThat(standing.worstThresholdPercent()).isEqualTo(GLOBAL_THRESHOLD);
    }

    @Test
    void aBreachingFilesystem_outranksAFullerOneThatIsNotBreaching() {
        MachineDiskStanding standing = standingOf(
            List.of(fs("/boot", 95), fs("/volume1", 86)),
            watches(new DiskWatch(NAS, "/boot", true, 99)));

        assertThat(standing.worstMountPoint()).isEqualTo("/volume1");
        assertThat(standing.level()).isEqualTo(DiskStandingLevel.BREACHING);
        assertThat(standing.breachingFilesystems()).isEqualTo(1);
        assertThat(standing.watchedFilesystems()).isEqualTo(2);
    }

    @Test
    void aMutedFilesystem_neverMakesAMachineLookLikeItIsInTrouble() {
        // The DSM system partition: 88% by design, forever. Nobody is judging it, so it is not the worst
        // thing true about this machine — it is not one of the things Vaier is saying anything about at all.
        MachineDiskStanding standing = standingOf(
            List.of(fs("/", 99), fs("/volume1", 40)),
            watches(new DiskWatch(NAS, "/", false, null)));

        assertThat(standing.worstMountPoint()).isEqualTo("/volume1");
        assertThat(standing.level()).isEqualTo(DiskStandingLevel.CLEAR);
        assertThat(standing.breachingFilesystems()).isZero();
        assertThat(standing.watchedFilesystems()).isEqualTo(1);
    }

    @Test
    void aMachineWhoseFilesystemsAreAllMuted_hasNoStandingAtAll() {
        // Not a clear standing — no standing. A card must draw nothing rather than a green mark nobody
        // earned.
        Optional<MachineDiskStanding> standing = MachineDiskStanding.of(NAS,
            List.of(fs("/", 99)), watches(new DiskWatch(NAS, "/", false, null)), GLOBAL_THRESHOLD);

        assertThat(standing).isEmpty();
    }

    @Test
    void aMachineNothingCouldBeReadFrom_hasNoStandingAtAll() {
        // Absence is not health: an unreadable disk is never a disk with room on it.
        assertThat(MachineDiskStanding.of(NAS, List.of(), watches(), GLOBAL_THRESHOLD)).isEmpty();
    }

    @Test
    void aFilesystemWithinOnePressureBandOfItsThreshold_isClosingOnIt() {
        assertThat(standingOf(List.of(fs("/volume1", 81)), watches()).level())
            .isEqualTo(DiskStandingLevel.CLOSING);
        assertThat(standingOf(List.of(fs("/volume1", 85)), watches()).level())
            .isEqualTo(DiskStandingLevel.CLOSING);
        // Equal-to is not above, so 85% against an 85% threshold is not yet breaching.
        assertThat(standingOf(List.of(fs("/volume1", 86)), watches()).level())
            .isEqualTo(DiskStandingLevel.BREACHING);
        assertThat(standingOf(List.of(fs("/volume1", 80)), watches()).level())
            .isEqualTo(DiskStandingLevel.CLEAR);
    }

    @Test
    void everyBreachingFilesystemIsCounted_notJustTheWorstOne() {
        MachineDiskStanding standing = standingOf(
            List.of(fs("/", 90), fs("/volume1", 99), fs("/data", 10)), watches());

        assertThat(standing.worstMountPoint()).isEqualTo("/volume1");
        assertThat(standing.breachingFilesystems()).isEqualTo(2);
        assertThat(standing.watchedFilesystems()).isEqualTo(3);
    }

    @Test
    void aStandingDiffersFromNothing_soTheFirstReadingAlwaysSpeaks() {
        assertThat(standingOf(List.of(fs("/", 40)), watches()).differsFrom(null)).isTrue();
    }

    @Test
    void anUnchangedSweepDiffersFromNothing_soTheFleetIsNotWokenEveryFiveMinutes() {
        MachineDiskStanding before = standingOf(List.of(fs("/", 40)), watches());
        MachineDiskStanding same = standingOf(List.of(fs("/", 40)), watches());
        MachineDiskStanding moved = standingOf(List.of(fs("/", 41)), watches());

        assertThat(same.differsFrom(before)).isFalse();
        assertThat(moved.differsFrom(before)).isTrue();
    }

    // --- retaining a reading (whoever took it) --------------------------------------------------------
    //
    // Both the five-minute sweep and a live look at a machine's disk pane take the same judged reading, so
    // "commit it and wake the fleet only if it moved" is one decision and belongs here — not once in the
    // watcher and once in the service, free to drift.

    /** Records what it is handed and hands back what it replaced, like the real in-memory hold. */
    private static final class HeldStandings implements ForHoldingMachineDiskStandings {
        private final Map<MachineId, MachineDiskStanding> held = new HashMap<>();

        @Override
        public Optional<MachineDiskStanding> record(MachineDiskStanding standing) {
            return Optional.ofNullable(held.put(standing.machineId(), standing));
        }

        @Override
        public Optional<MachineDiskStanding> forget(MachineId machineId) {
            return Optional.ofNullable(held.remove(machineId));
        }

        @Override
        public List<MachineDiskStanding> getAll() {
            return List.copyOf(held.values());
        }

        @Override
        public void retainOnly(Set<MachineId> machineIds) {
            held.keySet().retainAll(machineIds);
        }
    }

    /** Counts what the fleet was told, which is the only thing these tests care about. */
    private static final class CountingPublisher implements ForPublishingEvents {
        private int published;

        @Override
        public void publish(String topic, String eventName, String data) {
            published++;
        }
    }

    @Test
    void retain_commitsTheReading_andWakesTheFleetOnce() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();

        MachineDiskStanding.retain(NAS, List.of(fs("/", 90)), watches(), GLOBAL_THRESHOLD, held, publisher);

        assertThat(held.getAll()).singleElement()
            .satisfies(standing -> assertThat(standing.worstMountPoint()).isEqualTo("/"));
        assertThat(publisher.published).isEqualTo(1);
    }

    @Test
    void retain_readingTheSameDisksAgain_tellsTheFleetNothing() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();
        MachineDiskStanding.retain(NAS, List.of(fs("/", 90)), watches(), GLOBAL_THRESHOLD, held, publisher);

        MachineDiskStanding.retain(NAS, List.of(fs("/", 90)), watches(), GLOBAL_THRESHOLD, held, publisher);

        assertThat(publisher.published).isEqualTo(1);
    }

    @Test
    void retain_whenEveryFilesystemIsNowMuted_forgetsTheStandingAndSaysSo() {
        // The bug the operator hit, at the seam that fixes it: muting the last watched filesystem must
        // remove the machine's mark, not leave it frozen on the verdict nobody is making any more.
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();
        MachineDiskStanding.retain(NAS, List.of(fs("/", 90)), watches(), GLOBAL_THRESHOLD, held, publisher);

        MachineDiskStanding.retain(NAS, List.of(fs("/", 90)),
            watches(new DiskWatch(NAS, "/", false, null)), GLOBAL_THRESHOLD, held, publisher);

        assertThat(held.getAll()).isEmpty();
        assertThat(publisher.published).isEqualTo(2);
    }

    @Test
    void retain_withNothingToJudgeAndNothingHeld_wakesNobody() {
        HeldStandings held = new HeldStandings();
        CountingPublisher publisher = new CountingPublisher();

        MachineDiskStanding.retain(NAS, List.of(fs("/", 90)),
            watches(new DiskWatch(NAS, "/", false, null)), GLOBAL_THRESHOLD, held, publisher);

        assertThat(held.getAll()).isEmpty();
        assertThat(publisher.published).isZero();
    }
}
