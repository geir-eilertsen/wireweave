package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupRepositoryTest {

    private BackupServer server() {
        return new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new BackupRepository(" ", "nas-borg", "colina27", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
    }

    @Test
    void rejectsNameWithSpacesOrShellMetacharacters() {
        // A name is used verbatim as a shell/path token in every borg command, so it is an identifier: a
        // space, a command separator, or a substitution is rejected outright at construction (surfaces 400).
        for (String bad : new String[]{"NUC 02", "a b", "a; rm -rf ~", "a$(x)", "a`x`", "a|b", "a/b", ""}) {
            assertThatThrownBy(() -> new BackupRepository(bad, "nas-borg", null, "s3cr3t", false))
                .as("name %s", bad)
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsSafeIdentifierNames() {
        for (String ok : new String[]{"NUC-02", "colina27", "nas_borg"}) {
            assertThat(new BackupRepository(ok, "nas-borg", null, "s3cr3t", false).name()).isEqualTo(ok);
        }
    }

    @Test
    void sanitizedNameSlugsSpacesCollapsesRunsAndTrims() {
        assertThat(BackupRepository.sanitizedName("NUC 02")).isEqualTo("NUC-02");
        assertThat(BackupRepository.sanitizedName("  a   b  ")).isEqualTo("a-b");
        assertThat(BackupRepository.sanitizedName("-lead-and-trail-")).isEqualTo("lead-and-trail");
        assertThat(BackupRepository.sanitizedName("a;b$c")).isEqualTo("a-b-c");
        // Slugs to nothing / null -> a clear failure, mirroring PeerId.sanitized.
        assertThatThrownBy(() -> BackupRepository.sanitizedName("   "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupRepository.sanitizedName(";;;"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BackupRepository.sanitizedName(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRepoPathOverrideWithSpaceOrMetacharacter() {
        // The override legitimately holds '/' and '.', but a space or shell metacharacter is rejected.
        assertThatThrownBy(() -> new BackupRepository("colina27", "nas-borg", "/a b", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repoPath");
        assertThatThrownBy(() -> new BackupRepository("colina27", "nas-borg", "/a;rm", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class);
        // A normal path override is accepted.
        assertThat(new BackupRepository("colina27", "nas-borg", "./adopted-1.0", "s3cr3t", false).repoPath())
            .isEqualTo("./adopted-1.0");
    }

    @Test
    void rejectsBlankServerName() {
        assertThatThrownBy(() -> new BackupRepository("colina27", " ", "colina27", "s3cr3t", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serverName");
    }

    @Test
    void allowsNullOrBlankRepoPath() {
        // repoPath is a nullable override — a new repository derives its path from the server.
        assertThat(new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false).repoPath()).isNull();
        assertThat(new BackupRepository("colina27", "nas-borg", "  ", "s3cr3t", false).repoPath()).isEqualTo("  ");
    }

    @Test
    void repoPathOnDerivesBaseSlashNameWhenNoOverride() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false);
        assertThat(repo.repoPathOn(server())).isEqualTo("home/borg/backups/colina27");

        // Blank is treated as "derive" too.
        BackupRepository blank = new BackupRepository("colina27", "nas-borg", "  ", "s3cr3t", false);
        assertThat(blank.repoPathOn(server())).isEqualTo("home/borg/backups/colina27");
    }

    @Test
    void repoPathOnHonoursExplicitOverride() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./adopted", "s3cr3t", false);
        assertThat(repo.repoPathOn(server())).isEqualTo("./adopted");
    }

    @Test
    void borgRepoUrlRendersAbsoluteRemotePath() {
        // The server's baseRepoPath has NO leading slash and sshUrlPrefix ends at the port, so the URL
        // inserts exactly one '/' — producing an absolute remote path.
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", null, "s3cr3t", false);
        assertThat(repo.borgRepoUrl(server()))
            .isEqualTo("ssh://borg@192.168.3.3:8022/home/borg/backups/colina27");
    }

    @Test
    void borgRepoUrlHonoursExplicitOverride() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./adopted", "s3cr3t", false);
        assertThat(repo.borgRepoUrl(server())).isEqualTo("ssh://borg@192.168.3.3:8022/./adopted");
    }

    @Test
    void withPassphraseReplacesOnlyTheSecret() {
        BackupRepository repo = new BackupRepository("colina27", "nas-borg", "./adopted", null, true);
        BackupRepository withSecret = repo.withPassphrase("unlocked");
        assertThat(withSecret).isEqualTo(
            new BackupRepository("colina27", "nas-borg", "./adopted", "unlocked", true));
    }

    // --- a slug free of the ones already taken (§6.22: machine names need not be unique) -------------

    @Test
    void freeName_whenNothingIsTaken_isJustTheSanitisedName() {
        assertThat(BackupRepository.freeName("NUC 02", java.util.Set.of())).isEqualTo("NUC-02");
    }

    @Test
    void freeName_stepsAsideWhenTheSlugIsAlreadyTaken() {
        // Machine names stopped needing to be unique, and a machine's repository and job are both named
        // after it. Two machines called "NAS" would otherwise compute the same slug — the second would back
        // up into the FIRST one's borg repository, and its job would overwrite the first machine's job in
        // the store, which upserts by job name. The first machine would silently stop being backed up.
        assertThat(BackupRepository.freeName("NAS", java.util.Set.of("NAS"))).isEqualTo("NAS-2");
        assertThat(BackupRepository.freeName("NAS", java.util.Set.of("NAS", "NAS-2"))).isEqualTo("NAS-3");
    }

    @Test
    void freeName_comparesTheSanitisedForm_notTheRawInput() {
        assertThat(BackupRepository.freeName("NUC 02", java.util.Set.of("NUC-02"))).isEqualTo("NUC-02-2");
    }
}
