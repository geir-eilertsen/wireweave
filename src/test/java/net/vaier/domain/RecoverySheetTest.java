package net.vaier.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contents that survive Vaier — what a person reads after decrypting a {@link SurvivalKit}.
 *
 * <p>Everything Vaier knows about reading its own backups is, today, inside Vaier: the repository
 * passphrases are encrypted in its config store, and the key that decrypts them sits in the same directory.
 * That directory is backed up to the NAS — encrypted with a passphrase held in the store being backed up. So
 * losing the Vaier server leaves an encrypted repository whose passphrase is inside itself. This is the way
 * out of that circle, and it only works if a copy lives somewhere Vaier does not.
 *
 * <p>It is plain text because of where it is read: piped out of {@code openssl} into a terminal by someone
 * who has just lost their infrastructure. A rendered page would need a browser they may not have and would
 * survive on disk afterwards.
 */
class RecoverySheetTest {

    private BackupServer server() {
        return new BackupServer("nas-borg", TestMachineIds.of("NAS"), "192.168.3.3", 8022,
            "borg", "home/borg/backups", "/volume1/docker/borg", false);
    }

    private BackupRepository repo(String name, String pass) {
        return new BackupRepository(name, "nas-borg", null, pass, false);
    }

    private BackupJob job(String name, String machine, String repo) {
        return new BackupJob(name, TestMachineIds.of(machine), repo, List.of("/home/geir"), List.of(),
            7, 4, 6, "zstd,6", true, false);
    }

    /**
     * What the fleet's machines are called. The stores key on identity so a rename cannot orphan them, but
     * this page is read by someone who has just lost their fleet and has to recognise their own machines —
     * so the caller supplies the names rather than the sheet holding a copy that could go stale.
     */
    private static Map<MachineId, String> names(String... machineNames) {
        Map<MachineId, String> map = new LinkedHashMap<>();
        map.put(TestMachineIds.of("NAS"), "NAS");
        for (String name : machineNames) {
            map.put(TestMachineIds.of(name), name);
        }
        return map;
    }


    @Test
    void eachRepositoryAppears_withTheMachineItHolds_itsAddressAndItsPassphrase() {
        // Those three facts together are the whole point: without any one of them the archives are unreadable
        // even though you are holding them.
        String sheet = RecoverySheet.render(server(),
            List.of(repo("Apalveien-5", "s3cr3t-one")),
            List.of(job("Apalveien-5", "Apalveien 5", "Apalveien-5")), names("Apalveien 5"), "AAAAkey==");

        assertThat(sheet).contains("Apalveien 5");
        assertThat(sheet).contains("ssh://borg@192.168.3.3:8022/home/borg/backups/Apalveien-5");
        assertThat(sheet).contains("s3cr3t-one");
    }

    @Test
    void itCarriesTheCommandThatReadsThem_soNoVaierIsNeededToRecover() {
        // A passphrase with no instructions is a puzzle handed to someone on their worst day.
        String sheet = RecoverySheet.render(server(), List.of(repo("Apalveien-5", "p")),
            List.of(job("Apalveien-5", "Apalveien 5", "Apalveien-5")), names("Apalveien 5"), "AAAAkey==");

        assertThat(sheet).contains("borg list");
        assertThat(sheet).contains("borg extract");
        assertThat(sheet).contains("BORG_PASSPHRASE");
    }

    @Test
    void itCarriesTheConfigKeyToo_becauseARestoredVaierCannotReadItsOwnSecretsWithoutIt() {
        // The archives open with the passphrases alone. But restoring Vaier itself from one of them yields a
        // config store whose credentials, AWS secret and passphrases are all ciphertext — this is the key.
        String sheet = RecoverySheet.render(server(), List.of(repo("r", "p")), List.of(), names(), "AAAAkey==");

        assertThat(sheet).contains("AAAAkey==");
    }

    @Test
    void aRepositoryWithNoStoredPassphrase_saysSo_ratherThanPrintingABlank() {
        // A blank in this column would read as "no passphrase needed", which would send someone away from
        // the one repository that actually needs attention.
        String sheet = RecoverySheet.render(server(), List.of(repo("orphan", null)), List.of(), names(), "k");

        assertThat(sheet.toLowerCase()).contains("not stored");
    }

    @Test
    void aRepositoryNoMachineClaims_isStillOnTheSheet() {
        // Unclaimed stores hold real archives — a renamed machine's leftovers. Leaving them off would mean
        // the sheet quietly omits data that still exists.
        String sheet = RecoverySheet.render(server(), List.of(repo("NUC-02", "p")), List.of(), names(), "k");

        assertThat(sheet).contains("NUC-02");
    }

    @Test
    void itWarnsAboutTheDecryptedCopy_notAboutWhereTheKitIsKept() {
        // The kit itself is meant to live on fleet machines — encrypted, which is what makes that safe. What
        // must not linger is THIS, the decrypted contents: every key to every backup, in the clear.
        String sheet = RecoverySheet.render(server(), List.of(repo("r", "p")), List.of(), names(), "k");

        assertThat(sheet.toLowerCase()).contains("in the clear");
        assertThat(sheet.toLowerCase()).contains("delete");
    }

    @Test
    void itIsPlainText_becauseItIsReadInATerminalAfterOpenssl() {
        // Not a page: whoever reads this has lost their fleet and is piping openssl into less.
        String sheet = RecoverySheet.render(server(), List.of(repo("r", "p")), List.of(), names(), "k");

        assertThat(sheet).doesNotContain("<");
        assertThat(sheet).doesNotContain("&amp;");
    }

    @Test
    void withNoBackupServerThereIsNothingToRecover_andItSaysThat() {
        String sheet = RecoverySheet.render(null, List.of(), List.of(), names(), "k");

        assertThat(sheet.toLowerCase()).contains("no backup server");
    }

    // --- the sheet is the mapping, now that a repository is named by identity ------------------------

    @Test
    void aRepositoryNamedByIdentity_isStillReadAsItsMachine() {
        // Repositories are named after the machine's identity, so the directory on the server says nothing
        // to a person. This page is where that mapping lives once Vaier is gone — it has to carry both, the
        // machine's name and the repository the archives are actually in.
        MachineId colina = MachineId.generate();
        BackupRepository repo = new BackupRepository(colina.value(), "nas-borg", null, "s3cr3t", false);
        BackupJob job = new BackupJob("Colina 27", colina, colina.value(),
            List.of("/home"), List.of(), 7, 4, 6, "zstd,6", true, false);

        String sheet = RecoverySheet.render(server(), List.of(repo), List.of(job),
            Map.of(colina, "Colina 27"), "config-key");

        assertThat(sheet).contains("Colina 27");
        assertThat(sheet).as("and the directory those archives are actually in").contains(colina.value());
    }

    @Test
    void aRepositoryNoJobClaims_saysWhichMachineHasLeft_ratherThanNothing() {
        // The orphan, and it matters more than it did: the directory is an identity, so "no machine" alone
        // leaves an operator holding a UUID with nothing to attach it to. The name IS the machine, so say
        // which one — a machine that has left the fleet still has archives worth reading.
        MachineId departed = MachineId.generate();
        BackupRepository orphan = new BackupRepository(departed.value(), "nas-borg", null, "s3cr3t", false);

        String sheet = RecoverySheet.render(server(), List.of(orphan), List.of(),
            Map.of(), "config-key");

        // Asserted on the repository's OWN line: the server's line happens to carry the same phrase for its
        // own reasons, and a test that passes on that would pass whatever this line said.
        assertThat(sheet).contains("Backups of    a machine no longer in this fleet (" + departed.value() + ")");
    }
}
