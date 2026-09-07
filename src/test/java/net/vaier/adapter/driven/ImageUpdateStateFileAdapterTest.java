package net.vaier.adapter.driven;

import net.vaier.domain.RegistryDigestHistory;
import net.vaier.domain.ScopedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ImageUpdateStateFileAdapterTest {

    private static final ScopedImage VAULTWARDEN =
        new ScopedImage("11111111-1111-1111-1111-111111111111", "vaultwarden/server:latest");
    private static final ScopedImage TRAEFIK =
        new ScopedImage("22222222-2222-2222-2222-222222222222", "traefik:v3.6.14");

    @TempDir
    Path configDir;

    private ImageUpdateStateFileAdapter adapter() {
        return new ImageUpdateStateFileAdapter(configDir.toString());
    }

    private Path stateFile() {
        return configDir.resolve("update-available.yml");
    }

    @Test
    void anAbsentFileIsTheFirstBootStateOfKnowingNothing() {
        assertThat(adapter().loadOutOfDate()).isEmpty();
    }

    @Test
    void theRememberedImagesSurviveARestart() {
        // The whole reason this port exists: with the state in a field, every deploy re-announced the same
        // handful of genuinely out-of-date images.
        adapter().saveOutOfDate(Set.of(VAULTWARDEN, TRAEFIK));

        assertThat(adapter().loadOutOfDate()).containsExactlyInAnyOrder(VAULTWARDEN, TRAEFIK);
    }

    @Test
    void savingReplacesTheWholeSetRatherThanAddingToIt() {
        ImageUpdateStateFileAdapter adapter = adapter();
        adapter.saveOutOfDate(Set.of(VAULTWARDEN));

        adapter.saveOutOfDate(Set.of(TRAEFIK));

        assertThat(adapter.loadOutOfDate()).containsExactly(TRAEFIK);
    }

    @Test
    void savingNothingLeavesNothingRemembered() {
        ImageUpdateStateFileAdapter adapter = adapter();
        adapter.saveOutOfDate(Set.of(VAULTWARDEN));

        adapter.saveOutOfDate(Set.of());

        assertThat(adapter.loadOutOfDate()).isEmpty();
    }

    @Test
    void theSameImageOnTwoMachinesIsTwoRememberedEntries() {
        ScopedImage elsewhere = new ScopedImage(TRAEFIK.machineId(), VAULTWARDEN.image());
        adapter().saveOutOfDate(Set.of(VAULTWARDEN, elsewhere));

        assertThat(adapter().loadOutOfDate()).containsExactlyInAnyOrder(VAULTWARDEN, elsewhere);
    }

    @Test
    void aCorruptFileDegradesToKnowingNothingRatherThanBreakingTheSweep() throws IOException {
        // Erring towards noise: a lost latch re-announces an image, where a thrown exception would kill the
        // sweep and every alert in it.
        Files.writeString(stateFile(), "updateAvailable: [ this is not: valid yaml ][");

        assertThatCode(() -> assertThat(adapter().loadOutOfDate()).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    void anUnusableEntryIsSkippedAndTheRestAreKept() throws IOException {
        Files.writeString(stateFile(), """
            updateAvailable:
              - machineId: 11111111-1111-1111-1111-111111111111
              - machineId: 22222222-2222-2222-2222-222222222222
                image: traefik:v3.6.14
            """);

        assertThat(adapter().loadOutOfDate()).containsExactly(TRAEFIK);
    }

    @Test
    void aFileWhoseRootKeyIsMissingIsReadAsKnowingNothing() throws IOException {
        Files.writeString(stateFile(), "somethingElse: 3\n");

        assertThat(adapter().loadOutOfDate()).isEmpty();
    }

    // --- the second section: the registry answers that tell a moving tag from a settled one -------------

    private static final Instant DAY_ONE = Instant.parse("2026-09-01T01:40:00Z");

    /** Three nightly digests, a day apart — the netdata channel, as a store would hold it. */
    private static RegistryDigestHistory nightly() {
        return new RegistryDigestHistory(Map.of("netdata/netdata:latest", List.of(
            new RegistryDigestHistory.Answer("sha256:n1", DAY_ONE),
            new RegistryDigestHistory.Answer("sha256:n2", DAY_ONE.plus(Duration.ofDays(1))),
            new RegistryDigestHistory.Answer("sha256:n3", DAY_ONE.plus(Duration.ofDays(2))))));
    }

    @Test
    void anAbsentFileHasNoRegistryDigestHistoryEither() {
        assertThat(adapter().loadRegistryDigestHistory().answers()).isEmpty();
    }

    @Test
    void theDigestHistorySurvivesARestart_withTheInstantEachDigestWasFirstSeen() {
        // The instants are the rule, not decoration: without them a reloaded history cannot tell a nightly
        // from a tag that changed three times last quarter.
        adapter().saveRegistryDigestHistory(nightly());

        RegistryDigestHistory reloaded = adapter().loadRegistryDigestHistory();

        assertThat(reloaded.answers().get("netdata/netdata:latest"))
            .extracting(RegistryDigestHistory.Answer::firstSeen)
            .containsExactly(DAY_ONE, DAY_ONE.plus(Duration.ofDays(1)), DAY_ONE.plus(Duration.ofDays(2)));
        assertThat(reloaded.isMoving("netdata/netdata:latest", DAY_ONE.plus(Duration.ofDays(2)))).isTrue();
    }

    @Test
    void bothSectionsLiveInOneFileWithoutOverwritingEachOther() {
        // Two writers, one file. The latch is saved on every sweep and so is the history; either one
        // rewriting the whole document would silently drop the other's section.
        ImageUpdateStateFileAdapter adapter = adapter();
        adapter.saveOutOfDate(Set.of(VAULTWARDEN));
        adapter.saveRegistryDigestHistory(nightly());

        assertThat(adapter.loadOutOfDate()).containsExactly(VAULTWARDEN);
        assertThat(adapter.loadRegistryDigestHistory().answers())
            .containsOnlyKeys("netdata/netdata:latest");
    }

    @Test
    void theLatchSaveRedumpsTheHistoryItLoaded_withoutDegradingItsInstants() {
        // The order every sweep actually runs: history first, latch second. The latch save re-dumps the
        // history section it loaded, and a round trip that turned a firstSeen into a date would break the
        // moving-tag arithmetic silently.
        ImageUpdateStateFileAdapter adapter = adapter();
        adapter.saveRegistryDigestHistory(nightly());
        adapter.saveOutOfDate(Set.of(VAULTWARDEN));

        assertThat(adapter.loadRegistryDigestHistory().answers().get("netdata/netdata:latest"))
            .isEqualTo(nightly().answers().get("netdata/netdata:latest"));
    }

    @Test
    void aFileWrittenBeforeTheHistoryExistedStillLoadsItsLatch() throws IOException {
        // Every deployed Vaier has one of these. An upgrade that could not read it would re-announce every
        // out-of-date image in the fleet on its first sweep.
        Files.writeString(stateFile(), """
            updateAvailable:
              - machineId: 22222222-2222-2222-2222-222222222222
                image: traefik:v3.6.14
            """);

        assertThat(adapter().loadOutOfDate()).containsExactly(TRAEFIK);
        assertThat(adapter().loadRegistryDigestHistory().answers()).isEmpty();
    }

    @Test
    void anEntryWithNoUsableInstantIsSkipped_ratherThanDatedToNow() throws IOException {
        // Dating it to now would invent a change that just happened, which is exactly what makes a tag
        // read as moving. A skipped entry costs one relearned channel.
        Files.writeString(stateFile(), """
            registryDigests:
              netdata/netdata:latest:
              - digest: sha256:n1
                firstSeen: yesterday-ish
              - digest: sha256:n2
                firstSeen: '2026-09-02T01:40:00Z'
            """);

        assertThat(adapter().loadRegistryDigestHistory().answers().get("netdata/netdata:latest"))
            .extracting(RegistryDigestHistory.Answer::digest).containsExactly("sha256:n2");
    }

    @Test
    void aCorruptHistorySectionDegradesToKnowingNothingRatherThanBreakingTheSweep() throws IOException {
        Files.writeString(stateFile(), """
            registryDigests:
              netdata/netdata:latest: not-a-list
            """);

        assertThatCode(() -> assertThat(adapter().loadRegistryDigestHistory().answers()).isEmpty())
            .doesNotThrowAnyException();
    }

    @Test
    void savingAnEmptyHistoryForgetsWhatWasRemembered() {
        ImageUpdateStateFileAdapter adapter = adapter();
        adapter.saveRegistryDigestHistory(nightly());

        adapter.saveRegistryDigestHistory(RegistryDigestHistory.empty());

        assertThat(adapter.loadRegistryDigestHistory().answers()).isEmpty();
    }
}
