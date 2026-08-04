package net.vaier.domain;

import java.util.List;
import java.util.Optional;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Vaier upgrading itself: which container is Vaier, and whether there is anything to do about it.
 *
 * <p>This is the one container Vaier is allowed to pull. {@code ImageUpdateWatcher} states the rule for every
 * other one — detection is read-only, the operator's move is the operator's — and that rule survives here,
 * because upgrading yourself is not the same act as reaching into someone else's machine and restarting their
 * service. Vaier only ever does this to itself.
 */
class SelfUpgradeTest {

    private DockerService container(String name, String image, UpdateAvailability verdict) {
        return new DockerService("cid", name, image, "1.0", List.of(), List.of(), "running",
            "sha256:local", verdict);
    }

    @Test
    void vaierFindsItselfByImage_notByContainerName() {
        // A container called "vaier" on some other machine is not this process, and a compose project renamed
        // by its directory can call this one something else. The image repository is what actually identifies
        // Vaier's own image, and it is the thing the registry is asked about.
        List<DockerService> containers = List.of(
            container("traefik", "traefik:v3.1", UpdateAvailability.UPDATE_AVAILABLE),
            container("vaier-offline", "nginx:1.27-alpine", UpdateAvailability.UP_TO_DATE),
            container("vaier", "getvaier/vaier:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(SelfUpgrade.findSelf(containers)).isPresent();
        assertThat(SelfUpgrade.findSelf(containers).get().containerName()).isEqualTo("vaier");
    }

    @Test
    void anOfflinePageIsNotVaier() {
        // vaier-offline exists precisely to be up while Vaier is down. Mistaking it for Vaier would have the
        // upgrade recreate the one container that is supposed to survive the upgrade.
        assertThat(SelfUpgrade.findSelf(List.of(
            container("vaier-offline", "nginx:1.27-alpine", UpdateAvailability.UPDATE_AVAILABLE))))
            .isEmpty();
    }

    /** A registry serving {@code digest} for whatever it is asked about. */
    private static ForResolvingRegistryDigest registryServing(String digest) {
        ForResolvingRegistryDigest registry = mock(ForResolvingRegistryDigest.class);
        lenient().when(registry.resolveDigest(any())).thenReturn(Optional.ofNullable(digest));
        return registry;
    }

    private static List<DockerService> vaierRunning(UpdateAvailability verdict) {
        return List.of(new DockerService("cid", "vaier", "getvaier/vaier:latest", "1.0", List.of(),
            List.of(), "running", "sha256:local", verdict));
    }

    @Test
    void thereIsSomethingToDo_onlyWhenTheRegistryReallyServesSomethingElse() {
        assertThat(SelfUpgrade.upgradeAvailable(vaierRunning(UpdateAvailability.UNKNOWN),
            registryServing("sha256:newer"))).isTrue();
        assertThat(SelfUpgrade.upgradeAvailable(vaierRunning(UpdateAvailability.UNKNOWN),
            registryServing("sha256:local"))).isFalse();
        assertThat(SelfUpgrade.upgradeAvailable(List.of(), registryServing("sha256:newer"))).isFalse();
    }

    @Test
    void settingsAsksTheRegistryItself_ratherThanReadingTheUpdateSweepsVerdict() {
        // #353 stops sweeping Vaier's own stack, `vaier` included, so the verdict on this container is now
        // permanently UNKNOWN. Reading it would leave Settings blind to every upgrade there is — the one
        // path the operator said should speak for itself. It asks the registry directly instead.
        assertThat(SelfUpgrade.upgradeAvailable(vaierRunning(UpdateAvailability.UNKNOWN),
            registryServing("sha256:newer"))).isTrue();
    }

    @Test
    void aRegistryThatCannotAnswerIsNeverAReasonToRecreateTheControlPlane() {
        // Unreachable, rate-limited, or a locally-built image with no registry digest to compare. Treating
        // "cannot tell" as "upgrade" would turn a rate limit into an outage of the whole fleet's control
        // plane — and on a host that builds Vaier locally it would offer a DOWNGRADE to Hub's latest.
        assertThat(SelfUpgrade.upgradeAvailable(vaierRunning(UpdateAvailability.UNKNOWN),
            registryServing(null))).isFalse();

        ForResolvingRegistryDigest throwing = mock(ForResolvingRegistryDigest.class);
        when(throwing.resolveDigest(any())).thenThrow(new RuntimeException("429 Too Many Requests"));
        assertThat(SelfUpgrade.upgradeAvailable(vaierRunning(UpdateAvailability.UNKNOWN), throwing))
            .isFalse();
    }

    @Test
    void aContainerWithNoLocalDigestIsNeverUpgraded() {
        List<DockerService> noDigest = List.of(new DockerService("cid", "vaier", "getvaier/vaier:latest",
            "1.0", List.of(), List.of(), "running", null, UpdateAvailability.UNKNOWN));

        assertThat(SelfUpgrade.upgradeAvailable(noDigest, registryServing("sha256:newer"))).isFalse();
    }

    @Test
    void noVaierContainerMeansTheRegistryIsNeverAsked() {
        ForResolvingRegistryDigest registry = mock(ForResolvingRegistryDigest.class);

        assertThat(SelfUpgrade.upgradeAvailable(List.of(
            container("traefik", "traefik:v3.1", UpdateAvailability.UNKNOWN)), registry)).isFalse();

        verifyNoInteractions(registry);
    }
}
