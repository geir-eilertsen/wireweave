package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.SourceAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedAddressFileAdapterTest {

    @TempDir
    Path configDir;

    private TrustedAddressFileAdapter adapter() {
        return new TrustedAddressFileAdapter(configDir.toString());
    }

    @Test
    void withNoFileYet_nothingIsTrusted() {
        // The healthy first-boot state, not an error: nobody has trusted an address yet.
        assertThat(adapter().getAll()).isEmpty();
    }

    @Test
    void aTrustedAddressSurvivesARestart() {
        adapter().save(SourceAddress.of("195.178.110.155"));

        assertThat(adapter().getAll()).containsExactly(SourceAddress.of("195.178.110.155"));
    }

    @Test
    void trustingTheSameAddressTwiceStoresItOnce() {
        TrustedAddressFileAdapter adapter = adapter();
        adapter.save(SourceAddress.of("8.8.8.8"));
        adapter.save(SourceAddress.of("8.8.8.8"));

        assertThat(adapter.getAll()).containsExactly(SourceAddress.of("8.8.8.8"));
    }

    @Test
    void everyTrustedAddressIsKept() {
        TrustedAddressFileAdapter adapter = adapter();
        adapter.save(SourceAddress.of("8.8.8.8"));
        adapter.save(SourceAddress.of("1.1.1.1"));

        assertThat(adapter.getAll())
            .containsExactly(SourceAddress.of("8.8.8.8"), SourceAddress.of("1.1.1.1"));
    }

    /**
     * Same per-entry tolerance as {@code DiskWatchFileAdapter}: a row that will not make an address is
     * skipped, and every other trusted address still loads. A hand-edited file must not be able to quietly
     * un-trust the rest of the list.
     */
    @Test
    void aMalformedRowIsSkippedAndTheGoodOnesStillLoad() throws IOException {
        Files.writeString(configDir.resolve("trusted-addresses.yml"), """
            addresses:
            - address: 8.8.8.8
            - address: not-an-address
            - notTheKey: 1.1.1.1
            - address: 1.1.1.1
            """);

        assertThat(adapter().getAll())
            .containsExactly(SourceAddress.of("8.8.8.8"), SourceAddress.of("1.1.1.1"));
    }

    @Test
    void unreadableGarbageLoadsAsNothingRatherThanBlowingUp() throws IOException {
        Files.writeString(configDir.resolve("trusted-addresses.yml"), "\t: not: yaml: [");

        assertThat(adapter().getAll()).isEmpty();
    }

    // --- untrusting (#348) ---------------------------------------------------------------------------

    @Test
    void anUntrustedAddressDoesNotSurviveARestart() {
        TrustedAddressFileAdapter adapter = adapter();
        adapter.save(SourceAddress.of("8.8.8.8"));
        adapter.save(SourceAddress.of("1.1.1.1"));

        adapter.delete(SourceAddress.of("8.8.8.8"));

        assertThat(adapter().getAll()).containsExactly(SourceAddress.of("1.1.1.1"));
    }

    /**
     * Idempotent on purpose (#348). Two admins on the same list, or one double-click, must not turn the
     * second untrust into an error about a decision that has already been carried out — the operator asked
     * for this address not to be trusted, and it is not trusted.
     */
    @Test
    void untrustingAnAddressThatWasNeverTrustedIsNotAnError() {
        TrustedAddressFileAdapter adapter = adapter();
        adapter.save(SourceAddress.of("1.1.1.1"));

        adapter.delete(SourceAddress.of("8.8.8.8"));

        assertThat(adapter.getAll()).containsExactly(SourceAddress.of("1.1.1.1"));
    }

    @Test
    void untrustingWithNoFileYetIsNotAnError() {
        adapter().delete(SourceAddress.of("8.8.8.8"));

        assertThat(adapter().getAll()).isEmpty();
    }
}
