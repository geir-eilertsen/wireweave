package net.fjordomatic.domain;

import java.util.List;
import java.util.Optional;
import net.fjordomatic.domain.port.ForResolvingRegistryDigest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fjord updating itself: which container is Fjord, and whether there is anything to do about it.
 *
 * <p>This is the one container Fjord is allowed to pull. {@code ImageUpdateWatcher} states the rule for every
 * other one — detection is read-only, the operator's move is the operator's — and that rule survives here,
 * because updating yourself is not the same act as reaching into someone else's machine and restarting their
 * service. Fjord only ever does this to itself.
 */
class SelfUpdateTest {

    private DockerService container(String name, String image, UpdateAvailability verdict) {
        return new DockerService("cid", name, image, "1.0", List.of(), List.of(), "running",
            "sha256:local", verdict);
    }

    @Test
    void fjordFindsItselfByImage_notByContainerName() {
        // A container called "vaier" on some other machine is not this process, and a compose project renamed
        // by its directory can call this one something else. The image repository is what actually identifies
        // Fjord's own image, and it is the thing the registry is asked about.
        List<DockerService> containers = List.of(
            container("traefik", "traefik:v3.1", UpdateAvailability.UPDATE_AVAILABLE),
            container("vaier-offline", "nginx:1.27-alpine", UpdateAvailability.UP_TO_DATE),
            container("vaier", "getvaier/vaier:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(SelfUpdate.findSelf(containers)).isPresent();
        assertThat(SelfUpdate.findSelf(containers).get().containerName()).isEqualTo("vaier");
    }

    @Test
    void anOfflinePageIsNotFjord() {
        // vaier-offline exists precisely to be up while Fjord is down. Mistaking it for Fjord would have the
        // update recreate the one container that is supposed to survive the update.
        assertThat(SelfUpdate.findSelf(List.of(
            container("vaier-offline", "nginx:1.27-alpine", UpdateAvailability.UPDATE_AVAILABLE))))
            .isEmpty();
    }

    /** A registry serving {@code digest} for whatever it is asked about. */
    private static ForResolvingRegistryDigest registryServing(String digest) {
        ForResolvingRegistryDigest registry = mock(ForResolvingRegistryDigest.class);
        lenient().when(registry.resolveDigest(any())).thenReturn(Optional.ofNullable(digest));
        return registry;
    }

    private static List<DockerService> fjordRunning(UpdateAvailability verdict) {
        return List.of(new DockerService("cid", "vaier", "getvaier/vaier:latest", "1.0", List.of(),
            List.of(), "running", "sha256:local", verdict));
    }

    @Test
    void thereIsSomethingToDo_onlyWhenTheRegistryReallyServesSomethingElse() {
        assertThat(SelfUpdate.updateAvailable(fjordRunning(UpdateAvailability.UNKNOWN),
            registryServing("sha256:newer"))).isTrue();
        assertThat(SelfUpdate.updateAvailable(fjordRunning(UpdateAvailability.UNKNOWN),
            registryServing("sha256:local"))).isFalse();
        assertThat(SelfUpdate.updateAvailable(List.of(), registryServing("sha256:newer"))).isFalse();
    }

    @Test
    void settingsAsksTheRegistryItself_ratherThanReadingTheUpdateSweepsVerdict() {
        // #353 stops sweeping Fjord's own stack, `vaier` included, so the verdict on this container is now
        // permanently UNKNOWN. Reading it would leave Settings blind to every update there is — the one
        // path the operator said should speak for itself. It asks the registry directly instead.
        assertThat(SelfUpdate.updateAvailable(fjordRunning(UpdateAvailability.UNKNOWN),
            registryServing("sha256:newer"))).isTrue();
    }

    @Test
    void aRegistryThatCannotAnswerIsNeverAReasonToRecreateTheControlPlane() {
        // Unreachable, rate-limited, or a locally-built image with no registry digest to compare. Treating
        // "cannot tell" as "update" would turn a rate limit into an outage of the whole fleet's control
        // plane — and on a host that builds Fjord locally it would offer a DOWNGRADE to Hub's latest.
        assertThat(SelfUpdate.updateAvailable(fjordRunning(UpdateAvailability.UNKNOWN),
            registryServing(null))).isFalse();

        ForResolvingRegistryDigest throwing = mock(ForResolvingRegistryDigest.class);
        when(throwing.resolveDigest(any())).thenThrow(new RuntimeException("429 Too Many Requests"));
        assertThat(SelfUpdate.updateAvailable(fjordRunning(UpdateAvailability.UNKNOWN), throwing))
            .isFalse();
    }

    @Test
    void aContainerWithNoLocalDigestIsNeverUpdated() {
        List<DockerService> noDigest = List.of(new DockerService("cid", "vaier", "getvaier/vaier:latest",
            "1.0", List.of(), List.of(), "running", null, UpdateAvailability.UNKNOWN));

        assertThat(SelfUpdate.updateAvailable(noDigest, registryServing("sha256:newer"))).isFalse();
    }

    @Test
    void noFjordContainerMeansTheRegistryIsNeverAsked() {
        ForResolvingRegistryDigest registry = mock(ForResolvingRegistryDigest.class);

        assertThat(SelfUpdate.updateAvailable(List.of(
            container("traefik", "traefik:v3.1", UpdateAvailability.UNKNOWN)), registry)).isFalse();

        verifyNoInteractions(registry);
    }
}
