package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateRollupTest {

    private static ScopedImage on(String machine, String image) {
        return new ScopedImage(machine, image);
    }

    @Test
    void subjectNamesTheSingleImageAndItsMachineWhenOnlyOneWentOutOfDate() {
        // #57 refinement: the operator must be able to tell which machine to act on straight from the subject.
        ImageUpdateRollup rollup = new ImageUpdateRollup(
            List.of(on("Apalveien 5", "vaultwarden/server:latest")));

        assertThat(rollup.subject())
            .isEqualTo("[Fjord-O-Matic] Update available: vaultwarden/server:latest on Apalveien 5");
    }

    @Test
    void subjectCountsTheImagesWhenSeveralWentOutOfDateInOneSweep() {
        ImageUpdateRollup rollup = new ImageUpdateRollup(List.of(
            on("Apalveien 5", "a:1"), on("Colina 27", "b:1"), on("Fjord server", "c:1")));

        assertThat(rollup.subject()).isEqualTo("[Fjord-O-Matic] Update available: 3 images");
    }

    @Test
    void bodyListsEveryImageWithItsMachine() {
        ImageUpdateRollup rollup = new ImageUpdateRollup(List.of(
            on("Apalveien 5", "vaultwarden/server:latest"), on("Fjord server", "redis:7.2")));

        String body = rollup.body("example.com");

        assertThat(body).contains("Update available");
        assertThat(body).contains("vaultwarden/server:latest on Apalveien 5");
        assertThat(body).contains("redis:7.2 on Fjord server");
    }

    @Test
    void bodySaysFjordWillNotPullSoTheOperatorKnowsItIsTheirMove() {
        String body = new ImageUpdateRollup(List.of(on("Fjord server", "a:1"))).body("example.com");

        assertThat(body).containsIgnoringCase("does not pull");
    }

    @Test
    void bodyLinksTheFjordUiWhenABaseDomainIsConfigured() {
        String body = new ImageUpdateRollup(List.of(on("Fjord server", "a:1"))).body("example.com");

        assertThat(body).contains("https://vaier.example.com/");
    }

    @Test
    void bodyOmitsTheLinkWhenNoBaseDomainIsConfigured() {
        assertThat(new ImageUpdateRollup(List.of(on("Fjord server", "a:1"))).body(null))
            .doesNotContain("https://");
        assertThat(new ImageUpdateRollup(List.of(on("Fjord server", "a:1"))).body("  "))
            .doesNotContain("https://");
    }

    @Test
    void aRollupOfNothingIsNotWorthSending() {
        assertThat(new ImageUpdateRollup(List.of()).worthSending()).isFalse();
        assertThat(new ImageUpdateRollup(List.of(on("Fjord server", "a:1"))).worthSending()).isTrue();
    }
}
