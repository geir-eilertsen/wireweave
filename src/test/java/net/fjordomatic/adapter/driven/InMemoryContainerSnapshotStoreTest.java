package net.fjordomatic.adapter.driven;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.TestMachineIds;
import net.fjordomatic.domain.DockerService;
import net.fjordomatic.domain.DockerService.PortMapping;
import net.fjordomatic.domain.ScopedImage;
import net.fjordomatic.domain.UpdateAvailability;
import net.fjordomatic.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

class InMemoryContainerSnapshotStoreTest {

    private static final MachineId FJORD_SERVER = TestMachineIds.of("Fjord server");

    private final InMemoryContainerSnapshotStore store =
        new InMemoryContainerSnapshotStore("vaier-network", "172.20.0.1", () -> FJORD_SERVER);

    private static DockerService imaged(String name, String image) {
        return new DockerService("id-" + name, name, image, "v",
            List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")), List.of("vaier-network"), "running",
            "sha256:old", UpdateAvailability.UNKNOWN);
    }

    @Test
    void aFjordServerServiceNamesItsOwnerByIdentity() {
        // The publishable feed's owner used to be a NAME, matched against a machine's name to work out
        // which card the "publish me" nudge belonged to. The Fjord server is the one machine with no
        // store to look itself up in, so its identity comes from the port that owns that question.
        store.storeFjordServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));

        assertThat(store.getUnpublishedFjordServerServices(List.of()))
            .isNotEmpty()
            .allSatisfy(s -> assertThat(s.machineId()).isEqualTo(FJORD_SERVER.value()));
    }

    @Test
    void startsEmpty() {
        assertThat(store.discover()).isEmpty();
        assertThat(store.discoverAll()).isEmpty();
        assertThat(store.fjordServerContainers()).isEmpty();
        assertThat(store.peerContainers()).isEmpty();
        assertThat(store.imageUpdateVerdicts()).isEmpty();
    }

    @Test
    void discover_withNoVerdicts_returnsRawSnapshot() {
        store.storeFjordServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));

        assertThat(store.discover())
            .extracting(DockerService::updateAvailable)
            .containsExactly(UpdateAvailability.UNKNOWN);
    }

    @Test
    void discover_decoratesFjordServerContainersWithTheStoredVerdict() {
        store.storeFjordServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));
        store.storeImageUpdateVerdicts(Map.of(
            new ScopedImage(FJORD_SERVER.value(), "vaultwarden/server:latest"),
            UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(store.discover())
            .extracting(DockerService::updateAvailable)
            .containsExactly(UpdateAvailability.UPDATE_AVAILABLE);
    }

    @Test
    void discoverAll_decoratesEachPeersContainersUnderThatPeersIdentity() {
        store.storePeerContainers(List.of(new PeerContainers(
            TestMachineIds.of("apalveien5").value(), "apalveien5", "10.13.13.5", "OK",
            List.of(imaged("app", "some/app:latest")), false, "expected")));
        store.storeImageUpdateVerdicts(Map.of(
            new ScopedImage(TestMachineIds.of("apalveien5").value(), "some/app:latest"),
            UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(store.discoverAll()).singleElement()
            .extracting(p -> p.containers().get(0).updateAvailable())
            .isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
    }

    // --- forgetting one verdict (#352) ---

    @Test
    void forgettingOneVerdict_leavesTheContainerReadingUnknown_notUpToDate() {
        // Forgetting is the honest erasure: nothing has been re-measured, so the container falls back to
        // the same "no sweep has judged this" every un-swept container reports.
        store.storeFjordServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));
        ScopedImage image = new ScopedImage(FJORD_SERVER.value(), "vaultwarden/server:latest");
        store.storeImageUpdateVerdicts(Map.of(image, UpdateAvailability.UPDATE_AVAILABLE));

        store.forgetImageUpdateVerdict(image);

        assertThat(store.discover())
            .extracting(DockerService::updateAvailable)
            .containsExactly(UpdateAvailability.UNKNOWN);
        assertThat(store.imageUpdateVerdicts()).doesNotContainKey(image);
    }

    @Test
    void forgettingOneVerdict_leavesEveryOtherMachinesVerdictStanding() {
        // The narrow operation exists precisely so the update path never rewrites the whole map and
        // clobbers a sweep that landed between the pull and the settle.
        ScopedImage mine = new ScopedImage(FJORD_SERVER.value(), "vaultwarden/server:latest");
        ScopedImage theirs = new ScopedImage(TestMachineIds.of("apalveien5").value(), "some/app:latest");
        store.storeImageUpdateVerdicts(Map.of(
            mine, UpdateAvailability.UPDATE_AVAILABLE,
            theirs, UpdateAvailability.UPDATE_AVAILABLE));

        store.forgetImageUpdateVerdict(mine);

        assertThat(store.imageUpdateVerdicts())
            .containsExactly(entry(theirs, UpdateAvailability.UPDATE_AVAILABLE));
    }

    @Test
    void forgettingAVerdictNobodyHolds_changesNothing() {
        ScopedImage held = new ScopedImage(FJORD_SERVER.value(), "vaultwarden/server:latest");
        store.storeImageUpdateVerdicts(Map.of(held, UpdateAvailability.UPDATE_AVAILABLE));

        store.forgetImageUpdateVerdict(new ScopedImage(FJORD_SERVER.value(), "never/swept:latest"));

        assertThat(store.imageUpdateVerdicts())
            .containsExactly(entry(held, UpdateAvailability.UPDATE_AVAILABLE));
    }
}
