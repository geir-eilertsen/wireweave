package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files a backup run could not read — parsed out of borg's own output. This is the evidence behind an
 * {@link BackupRunStatus#INCOMPLETE} run, so what counts as an unreadable file is pinned here.
 */
class UnreadableFilesTest {

    @Test
    void readsTheDeniedPathsOutOfBorgsOutput() {
        // Verbatim from the Colina 27 run that ran as the SSH user over /home: borg names the path it could
        // not open, then its errno. The path is what an operator needs — not borg's phrasing around it.
        String output = """
            Creating archive at "ssh://borg@nas:8022/./colina#{now}"
            /home/nut-http/logs/2026-04-04-14-07-07.log: open: [Errno 13] Permission denied: '2026-04-04-14-07-07.log'
            /home/nut-http/logs/2026-04-05-01-02-03.log: open: [Errno 13] Permission denied: '2026-04-05-01-02-03.log'
            """;

        UnreadableFiles unreadable = UnreadableFiles.from(output);

        assertThat(unreadable.any()).isTrue();
        assertThat(unreadable.total()).isEqualTo(2);
        assertThat(unreadable.sample()).containsExactly(
            "/home/nut-http/logs/2026-04-04-14-07-07.log",
            "/home/nut-http/logs/2026-04-05-01-02-03.log");
    }

    @Test
    void readsTheOtherShapesBorgUsesForTheSameDenial() {
        // borg names the syscall it was denied on, and older versions name none at all. All three mean the
        // same thing to an operator: that file is not in the archive.
        String output = """
            /var/lib/docker/volumes/db/_data: scandir: [Errno 13] Permission denied: '_data'
            /home/geir/.ssh/id_ed25519: [Errno 13] Permission denied: 'id_ed25519'
            /srv/mail: opendir: [Errno 13] Permission denied: 'mail'
            """;

        assertThat(UnreadableFiles.from(output).sample()).containsExactly(
            "/var/lib/docker/volumes/db/_data",
            "/home/geir/.ssh/id_ed25519",
            "/srv/mail");
    }

    @Test
    void capsTheRetainedSampleButKeepsTheTrueCount() {
        // A non-root run over a busy /home emits thousands of these lines. The sample is what gets shown and
        // mailed, so it is bounded; the count is not, because "how much did we lose" is the whole point.
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            output.append("/home/nut-http/logs/log-").append(i)
                .append(".log: open: [Errno 13] Permission denied: 'log-").append(i).append(".log'\n");
        }

        UnreadableFiles unreadable = UnreadableFiles.from(output.toString());

        assertThat(unreadable.total()).isEqualTo(250);
        assertThat(unreadable.sample()).hasSize(UnreadableFiles.SAMPLE_LIMIT);
        assertThat(unreadable.sample().get(0)).isEqualTo("/home/nut-http/logs/log-0.log");
    }

    @Test
    void aCleanRunOrProseAboutPermissionsIsNotAnUnreadableFile() {
        // Only borg's own per-file denial line counts. A human sentence that happens to say "permission
        // denied" must never turn a run into an incomplete one — the evidence has to be a named path.
        assertThat(UnreadableFiles.from("2 files skipped (permission denied)").any()).isFalse();
        assertThat(UnreadableFiles.from("Archive name: colina-2026-07-23").any()).isFalse();
        assertThat(UnreadableFiles.from("").any()).isFalse();
        assertThat(UnreadableFiles.from(null).any()).isFalse();
        assertThat(UnreadableFiles.from(null).sample()).isEmpty();
        assertThat(UnreadableFiles.from(null).total()).isZero();
    }

    @Test
    void theSameFileDeniedTwiceIsCountedOnce() {
        // borg can hit the same path on more than one pass. An operator counting "how many files did I lose"
        // must not be told two when one file is missing.
        String output = """
            /home/nut-http/logs/a.log: open: [Errno 13] Permission denied: 'a.log'
            /home/nut-http/logs/a.log: open: [Errno 13] Permission denied: 'a.log'
            """;

        assertThat(UnreadableFiles.from(output).total()).isEqualTo(1);
    }

    @Test
    void itRendersTheOperatorsAccountOfWhatIsMissing() {
        // The words that reach an admin's inbox: how many files, which ones, and — because a name alone is
        // not an action — the setting that would have read them.
        String output = """
            /home/nut-http/logs/a.log: open: [Errno 13] Permission denied: 'a.log'
            /home/nut-http/logs/b.log: open: [Errno 13] Permission denied: 'b.log'
            """;

        String report = UnreadableFiles.from(output).report();

        assertThat(report).contains("2 files could not be read");
        assertThat(report).contains("are NOT in the archive");
        assertThat(report).contains("/home/nut-http/logs/a.log");
        assertThat(report).contains("Back up as root");
    }

    @Test
    void aCappedReportSaysItIsOnlyShowingSome() {
        // Ten paths under a count of 250 would read as "ten files lost" unless the report says otherwise.
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            output.append("/home/x/log-").append(i).append(".log: open: [Errno 13] Permission denied: 'x'\n");
        }

        String report = UnreadableFiles.from(output.toString()).report();

        assertThat(report).contains("250 files could not be read");
        assertThat(report).contains("showing the first " + UnreadableFiles.SAMPLE_LIMIT);
    }

    // --- the one-line account, for a place with room for one line (the machine's nudge) ---

    @Test
    void itRendersTheLossAsOneLineNamingTheFilesAndWhatItCost() {
        // The nudge card has one line for the "why". It has to name the files (so the operator recognises
        // the data) AND say what their absence means — a list of paths alone is not a reason to act.
        String output = """
            /home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'
            /home/pihole/gravity.db: open: [Errno 13] Permission denied: 'gravity.db'
            """;

        String line = UnreadableFiles.from(output).inOneLine();

        assertThat(line).doesNotContain("\n");
        assertThat(line).contains("/home/mqtt/data/mosquitto.db", "/home/pihole/gravity.db");
        assertThat(line).contains("belong to other users");
        assertThat(line).contains("holes");
    }

    @Test
    void aSingleLostFileIsSpokenAboutInTheSingular() {
        String output = "/home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'\n";

        String line = UnreadableFiles.from(output).inOneLine();

        assertThat(line).contains("it belongs to another user");
        assertThat(line).contains("a hole");
    }

    @Test
    void theOneLineNamesOnlyAFewFilesAndCountsTheRest() {
        // Ten paths on one line is not a line. It names a handful and is honest about the remainder — the
        // exact total is still what decides whether the operator acts.
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            output.append("/home/x/log-").append(i).append(".log: open: [Errno 13] Permission denied: 'x'\n");
        }

        String line = UnreadableFiles.from(output.toString()).inOneLine();

        assertThat(line).contains("/home/x/log-0.log");
        assertThat(line).doesNotContain("/home/x/log-" + UnreadableFiles.INLINE_LIMIT + ".log");
        assertThat(line).contains("and " + (40 - UnreadableFiles.INLINE_LIMIT) + " more");
    }

    @Test
    void aCleanRunHasNoOneLineToRender() {
        assertThat(UnreadableFiles.from("all good").inOneLine()).isEmpty();
    }
}
