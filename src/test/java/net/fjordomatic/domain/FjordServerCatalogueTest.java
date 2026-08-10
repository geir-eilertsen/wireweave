package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FjordServerCatalogueTest {

    @Test
    void isExcluded_hidesTheContainersOfFjordsOwnStack() {
        // The edge, the tunnel and Fjord itself.
        assertThat(FjordServerCatalogue.isExcluded("vaier")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("wireguard")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("wireguard-masquerade")).isTrue();

        // The social-login chain — already published at oauth2.<domain> and dex.<domain>.
        assertThat(FjordServerCatalogue.isExcluded("oauth2-proxy")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("dex")).isTrue();

        // The edge's threat detection.
        assertThat(FjordServerCatalogue.isExcluded("crowdsec")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("crowdsec-bouncer")).isTrue();

        // The offline placeholder and the log rotator.
        assertThat(FjordServerCatalogue.isExcluded("vaier-offline")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("traefik-logrotate")).isTrue();

        // The LAN-route sidecars.
        assertThat(FjordServerCatalogue.isExcluded("host-lan-routes")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("traefik-lan-routes")).isTrue();

        // The one-shot init sidecars.
        assertThat(FjordServerCatalogue.isExcluded("vaier-init")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("oauth2-proxy-init")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("dex-init")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("geoip-init")).isTrue();
    }

    @Test
    void isExcluded_hidesTheDockerSocketProxy() {
        // The sharpest one: docker-proxy exposes 2375, the unauthenticated Docker API. Offering it as a
        // publishable service invites an operator to put root on every container behind a public hostname.
        assertThat(FjordServerCatalogue.isExcluded("docker-proxy")).isTrue();
    }

    @Test
    void isExcluded_stillHidesTheDecommissionedAutheliaStack() {
        // Removed from the stack in #305, but an updated host can still be carrying the containers.
        assertThat(FjordServerCatalogue.isExcluded("authelia")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("redis")).isTrue();
    }

    @Test
    void isExcluded_isCaseInsensitiveAndFalseForOrdinaryContainers() {
        assertThat(FjordServerCatalogue.isExcluded("WireGuard")).isTrue();
        assertThat(FjordServerCatalogue.isExcluded("grafana")).isFalse();
        // The operator's own containers on the Fjord host stay offered.
        assertThat(FjordServerCatalogue.isExcluded("pihole")).isFalse();
    }

    @Test
    void isFjordOwnStack_countsTheOfferedCarveOutAsFjordsOwnToo() {
        // Offered is not the opposite of Fjord's own: Traefik is offered for publishing and is still part
        // of the stack a Fjord release pins. Asking isExcluded alone would call it the operator's.
        assertThat(FjordServerCatalogue.isFjordOwnStack("traefik")).isTrue();
        assertThat(FjordServerCatalogue.isFjordOwnStack("vaier")).isTrue();
        assertThat(FjordServerCatalogue.isFjordOwnStack("docker-proxy")).isTrue();
        assertThat(FjordServerCatalogue.isFjordOwnStack("pihole")).isFalse();
        assertThat(FjordServerCatalogue.isFjordOwnStack("Traefik")).isTrue();
    }

    @Test
    void isOffered_isTheCarveOutForFjordsOwnContainersThatAreWorthPublishing() {
        assertThat(FjordServerCatalogue.isOffered("traefik")).isTrue();
        assertThat(FjordServerCatalogue.isOffered("crowdsec")).isFalse();
        // Not a claim about the operator's containers — those are offered because they aren't excluded.
        assertThat(FjordServerCatalogue.isOffered("grafana")).isFalse();
    }

    @Test
    void isPublishablePort_restrictsKnownServicesToTheirListedPorts() {
        assertThat(FjordServerCatalogue.isPublishablePort("traefik", 8080)).isTrue();
        assertThat(FjordServerCatalogue.isPublishablePort("traefik", 80)).isFalse();
        assertThat(FjordServerCatalogue.isPublishablePort("traefik", 443)).isFalse();
    }

    @Test
    void isPublishablePort_allowsEveryPortOfAnUnknownContainer() {
        assertThat(FjordServerCatalogue.isPublishablePort("grafana", 3000)).isTrue();
    }

    @Test
    void rootRedirectPath_returnsTheKnownPathOrNull() {
        assertThat(FjordServerCatalogue.rootRedirectPath("traefik")).isEqualTo("/dashboard/");
        assertThat(FjordServerCatalogue.rootRedirectPath("grafana")).isNull();
    }

    /**
     * The catalogue's blind spot has always been drift: it was written against a stack that had Authelia
     * and Redis in it and was never revisited when oauth2-proxy, Dex, CrowdSec, the socket proxy and the
     * offline page arrived — so every one of those was quietly on offer. A hand-maintained list of a
     * file's contents goes stale silently, so bind it to the file: every container Fjord's own compose
     * stack starts must be classified here, either hidden or deliberately offered. Adding a service to
     * docker-compose.yml without deciding which it is now fails the build.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyContainerInFjordsOwnStackIsEitherHiddenOrDeliberatelyOffered() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        List<String> unclassified = new ArrayList<>();
        for (Object definition : services.values()) {
            Object containerName = ((Map<String, Object>) definition).get("container_name");
            if (containerName == null) continue;
            String name = containerName.toString();
            if (!FjordServerCatalogue.isExcluded(name) && !FjordServerCatalogue.isOffered(name)) {
                unclassified.add(name);
            }
        }

        assertThat(unclassified)
            .as("containers of Fjord's own stack that the catalogue neither hides nor offers")
            .isEmpty();
    }

    // --- what the update sweep may ask about (#353) ---

    private static DockerService running(String name, String image) {
        return new DockerService("id-" + name, name, image, "v",
            List.of(), List.of(), "running", "sha256:local", UpdateAvailability.UNKNOWN);
    }

    @Test
    void vaiersOwnStackIsNotSweptAtAll_soNoRegistryIsAskedAboutAnImageNobodyCanActOn() {
        // The images move with a Fjord release, so the only honest resolution of a mark on one is "wait for
        // a Fjord release" — an alert an operator cannot act on teaches them to filter the channel. Dropped
        // BEFORE the registries are asked, not swept-and-hidden: it should not spend the rate limit either.
        List<DockerService> fjordServerContainers = List.of(
            running("traefik", "traefik:v3.6.14"),
            running("wireguard", "linuxserver/wireguard:latest"),
            running("pihole", "pihole/pihole:latest"));

        assertThat(FjordServerCatalogue.sweepable(fjordServerContainers))
            .extracting(DockerService::containerName)
            .containsExactly("pihole");
    }

    @Test
    void fjordItselfIsNotSweptEither_becauseSettingsSpeaksForIt() {
        // The plan of record kept `vaier` because Settings has a real button. The operator overruled it:
        // Settings asks for itself, and on a box that builds Fjord locally the local digest differs from
        // Hub's `latest`, so the mark would read "newer available" when acting on it would DOWNGRADE.
        // A mark that talks you into a downgrade is worse than no mark, so there is no exception at all.
        assertThat(FjordServerCatalogue.sweepable(List.of(running("vaier", "getvaier/vaier:latest"))))
            .isEmpty();
    }

    @Test
    void theOfferedHalfOfTheStackIsDroppedToo_notJustTheHiddenHalf() {
        // Traefik is OFFERED for publishing and is still part of the stack a Fjord release pins — which is
        // exactly the container this issue was filed about. Offered is not the opposite of Fjord's own.
        assertThat(FjordServerCatalogue.isOffered("traefik")).isTrue();
        assertThat(FjordServerCatalogue.sweepable(List.of(running("traefik", "traefik:v3.6.14")))).isEmpty();
    }

    @Test
    void sweepableIsNullSafeAndKeepsEverythingItDoesNotRecognise() {
        assertThat(FjordServerCatalogue.sweepable(null)).isEmpty();
        assertThat(FjordServerCatalogue.sweepable(List.of(running("openhab", "openhab:latest"))))
            .hasSize(1);
    }
}
