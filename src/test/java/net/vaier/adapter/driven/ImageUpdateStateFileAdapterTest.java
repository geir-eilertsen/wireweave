package net.vaier.adapter.driven;

import net.vaier.domain.ScopedImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
