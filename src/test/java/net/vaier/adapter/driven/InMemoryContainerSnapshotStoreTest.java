package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.TestMachineIds;
import net.vaier.domain.DockerService;
import net.vaier.domain.DockerService.PortMapping;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.UpdateAvailability;
import net.vaier.domain.port.ForDiscoveringPeerContainers.PeerContainers;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryContainerSnapshotStoreTest {

    private static final MachineId VAIER_SERVER = TestMachineIds.of("Vaier server");

    private final InMemoryContainerSnapshotStore store =
        new InMemoryContainerSnapshotStore("vaier-network", "172.20.0.1", () -> VAIER_SERVER);

    private static DockerService imaged(String name, String image) {
        return new DockerService("id-" + name, name, image, "v",
            List.of(new PortMapping(80, 8080, "tcp", "0.0.0.0")), List.of("vaier-network"), "running",
            "sha256:old", UpdateAvailability.UNKNOWN);
    }

    @Test
    void aVaierServerServiceNamesItsOwnerByIdentity() {
        // The publishable feed's owner used to be a NAME, matched against a machine's name to work out
        // which card the "publish me" nudge belonged to. The Vaier server is the one machine with no
        // store to look itself up in, so its identity comes from the port that owns that question.
        store.storeVaierServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));

        assertThat(store.getUnpublishedVaierServerServices(List.of()))
            .isNotEmpty()
            .allSatisfy(s -> assertThat(s.machineId()).isEqualTo(VAIER_SERVER.value()));
    }

    @Test
    void startsEmpty() {
        assertThat(store.discover()).isEmpty();
        assertThat(store.discoverAll()).isEmpty();
        assertThat(store.vaierServerContainers()).isEmpty();
        assertThat(store.peerContainers()).isEmpty();
        assertThat(store.imageUpdateVerdicts()).isEmpty();
    }

    @Test
    void discover_withNoVerdicts_returnsRawSnapshot() {
        store.storeVaierServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));

        assertThat(store.discover())
            .extracting(DockerService::updateAvailable)
            .containsExactly(UpdateAvailability.UNKNOWN);
    }

    @Test
    void discover_decoratesVaierServerContainersWithTheStoredVerdict() {
        store.storeVaierServerContainers(List.of(imaged("vaultwarden", "vaultwarden/server:latest")));
        store.storeImageUpdateVerdicts(Map.of(
            new ScopedImage(VAIER_SERVER.value(), "vaultwarden/server:latest"),
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
}
