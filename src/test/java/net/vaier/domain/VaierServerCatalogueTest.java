package net.vaier.domain;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VaierServerCatalogueTest {

    @Test
    void isExcluded_hidesTheContainersOfVaiersOwnStack() {
        // The edge, the tunnel and Vaier itself.
        assertThat(VaierServerCatalogue.isExcluded("vaier")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("wireguard")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("wireguard-masquerade")).isTrue();

        // The social-login chain — already published at oauth2.<domain> and dex.<domain>.
        assertThat(VaierServerCatalogue.isExcluded("oauth2-proxy")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("dex")).isTrue();

        // The edge's threat detection.
        assertThat(VaierServerCatalogue.isExcluded("crowdsec")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("crowdsec-bouncer")).isTrue();

        // The offline placeholder and the log rotator.
        assertThat(VaierServerCatalogue.isExcluded("vaier-offline")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("traefik-logrotate")).isTrue();

        // The LAN-route sidecars.
        assertThat(VaierServerCatalogue.isExcluded("host-lan-routes")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("traefik-lan-routes")).isTrue();

        // The one-shot init sidecars.
        assertThat(VaierServerCatalogue.isExcluded("vaier-init")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("oauth2-proxy-init")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("dex-init")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("geoip-init")).isTrue();
    }

    @Test
    void isExcluded_hidesTheDockerSocketProxy() {
        // The sharpest one: docker-proxy exposes 2375, the unauthenticated Docker API. Offering it as a
        // publishable service invites an operator to put root on every container behind a public hostname.
        assertThat(VaierServerCatalogue.isExcluded("docker-proxy")).isTrue();
    }

    @Test
    void isExcluded_stillHidesTheDecommissionedAutheliaStack() {
        // Removed from the stack in #305, but an upgraded host can still be carrying the containers.
        assertThat(VaierServerCatalogue.isExcluded("authelia")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("redis")).isTrue();
    }

    @Test
    void isExcluded_isCaseInsensitiveAndFalseForOrdinaryContainers() {
        assertThat(VaierServerCatalogue.isExcluded("WireGuard")).isTrue();
        assertThat(VaierServerCatalogue.isExcluded("grafana")).isFalse();
        // The operator's own containers on the Vaier host stay offered.
        assertThat(VaierServerCatalogue.isExcluded("pihole")).isFalse();
    }

    @Test
    void isOffered_isTheCarveOutForVaiersOwnContainersThatAreWorthPublishing() {
        assertThat(VaierServerCatalogue.isOffered("traefik")).isTrue();
        assertThat(VaierServerCatalogue.isOffered("crowdsec")).isFalse();
        // Not a claim about the operator's containers — those are offered because they aren't excluded.
        assertThat(VaierServerCatalogue.isOffered("grafana")).isFalse();
    }

    @Test
    void isPublishablePort_restrictsKnownServicesToTheirListedPorts() {
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 8080)).isTrue();
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 80)).isFalse();
        assertThat(VaierServerCatalogue.isPublishablePort("traefik", 443)).isFalse();
    }

    @Test
    void isPublishablePort_allowsEveryPortOfAnUnknownContainer() {
        assertThat(VaierServerCatalogue.isPublishablePort("grafana", 3000)).isTrue();
    }

    @Test
    void rootRedirectPath_returnsTheKnownPathOrNull() {
        assertThat(VaierServerCatalogue.rootRedirectPath("traefik")).isEqualTo("/dashboard/");
        assertThat(VaierServerCatalogue.rootRedirectPath("grafana")).isNull();
    }

    /**
     * The catalogue's blind spot has always been drift: it was written against a stack that had Authelia
     * and Redis in it and was never revisited when oauth2-proxy, Dex, CrowdSec, the socket proxy and the
     * offline page arrived — so every one of those was quietly on offer. A hand-maintained list of a
     * file's contents goes stale silently, so bind it to the file: every container Vaier's own compose
     * stack starts must be classified here, either hidden or deliberately offered. Adding a service to
     * docker-compose.yml without deciding which it is now fails the build.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyContainerInVaiersOwnStackIsEitherHiddenOrDeliberatelyOffered() throws Exception {
        Map<String, Object> compose = (Map<String, Object>) new Yaml()
            .load(Files.readString(Path.of("docker-compose.yml")));
        Map<String, Object> services = (Map<String, Object>) compose.get("services");

        List<String> unclassified = new ArrayList<>();
        for (Object definition : services.values()) {
            Object containerName = ((Map<String, Object>) definition).get("container_name");
            if (containerName == null) continue;
            String name = containerName.toString();
            if (!VaierServerCatalogue.isExcluded(name) && !VaierServerCatalogue.isOffered(name)) {
                unclassified.add(name);
            }
        }

        assertThat(unclassified)
            .as("containers of Vaier's own stack that the catalogue neither hides nor offers")
            .isEmpty();
    }
}
