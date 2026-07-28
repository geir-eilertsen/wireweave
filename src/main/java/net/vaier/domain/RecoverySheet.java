package net.vaier.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What survives Vaier: every key to every backup, and the commands that use them.
 *
 * <p><b>Why this has to exist.</b> Everything needed to read Vaier's backups is currently inside Vaier. The
 * repository passphrases are encrypted in its config store; the key that decrypts them sits in the same
 * directory; and that directory is itself backed up to the backup server — encrypted with a passphrase held
 * in the store being backed up. Losing the Vaier server therefore leaves an encrypted repository whose
 * passphrase is inside itself, and every other machine's archives in the same position. Nothing warns about
 * it, because nothing is broken until the day it all is.
 *
 * <p><b>Why plain text.</b> This is read by a person who has just lost their fleet, piping {@link SurvivalKit}
 * through {@code openssl} into a terminal. A rendered page would ask them for a browser they may not have,
 * and would survive on disk after they closed it.
 *
 * <p><b>Its safety is not its own.</b> These contents are in the clear by design — they have to work for
 * someone with a laptop, no fleet and no Vaier, so they cannot be locked behind anything that was lost with
 * it. What protects them is the {@link SurvivalKit} envelope around them, and the passphrase that is only in
 * the operator's head.
 *
 * <p>Rendered here rather than in a controller for the same reason the borg setup scripts are: what this must
 * contain to be sufficient is a domain rule, and getting it wrong is only discovered on the worst day.
 */
public final class RecoverySheet {

    private RecoverySheet() {}

    /**
     * Render the contents. {@code jobs} only supplies which machine each repository holds — a repository no
     * job claims is still listed, because it still holds archives, and omitting it would mean the contents
     * quietly leave out data that exists.
     *
     * <p>{@code machineNames} turns machine identities into names. The stores are keyed by {@link MachineId}
     * so a rename cannot orphan them, but this page is read by a person who has lost their fleet and needs to
     * recognise their own machines — a UUID here would be the one thing that made the page useless on the day
     * it matters. A machine that is no longer in the fleet says so, rather than being dropped or invented.
     */
    public static String render(BackupServer server, List<BackupRepository> repositories,
                                List<BackupJob> jobs, Map<MachineId, String> machineNames,
                                String configKey) {
        StringBuilder sb = new StringBuilder();
        sb.append("VAIER — HOW TO READ THIS FLEET'S BACKUPS\n");
        sb.append("========================================\n\n");
        sb.append("You are holding every passphrase to every backup, in the clear. That is what makes this\n");
        sb.append("work on a day when there is no Vaier left to ask. If you redirected this to a file,\n");
        sb.append("delete that file when you are done.\n\n");

        if (server == null) {
            sb.append("There is NO BACKUP SERVER in this fleet, so nothing is being backed up and there is\n");
            sb.append("nothing here to recover.\n");
            return sb.toString();
        }

        sb.append("WHERE THE BACKUPS ARE\n");
        sb.append("---------------------\n");
        row(sb, "Machine", nameOf(server.machineId(), machineNames));
        row(sb, "Reached at", server.host() + ":" + server.sshPort());
        row(sb, "Borg user", server.borgUser());
        sb.append("\n");

        sb.append("REPOSITORIES\n");
        sb.append("------------\n");
        if (repositories == null || repositories.isEmpty()) {
            sb.append("None yet — nothing has been backed up.\n");
        } else {
            for (BackupRepository repo : repositories) {
                row(sb, "Backups of", machineFor(repo, jobs, machineNames));
                row(sb, "Repository", repo.borgRepoUrl(server));
                // A blank here would read as "no passphrase needed" and send someone away from the one
                // repository that actually needs attention.
                row(sb, "Passphrase", repo.passphrase() == null || repo.passphrase().isBlank()
                    ? "NOT STORED — Vaier does not hold this one; find it yourself before you need it"
                    : repo.passphrase());
                sb.append("\n");
            }
        }

        sb.append("READING AN ARCHIVE WITH NO VAIER\n");
        sb.append("--------------------------------\n");
        sb.append("On any machine with borg installed and network to the server above:\n\n");
        sb.append("  export BORG_PASSPHRASE='the passphrase above'\n");
        sb.append("  borg list    'the repository above'\n");
        sb.append("  borg extract 'the repository above'::ARCHIVE-NAME\n\n");
        sb.append("It extracts into the current directory. Nothing else is needed — not this fleet, not the\n");
        sb.append("Vaier server, not the key below.\n\n");

        sb.append("VAIER'S OWN CONFIGURATION KEY\n");
        sb.append("-----------------------------\n");
        sb.append("The archives above open with their passphrases alone. This key is for the step after:\n");
        sb.append("restoring Vaier itself from one of them yields a configuration whose stored credentials,\n");
        sb.append("AWS secret and passphrases are all ciphertext, and this is what decrypts them.\n\n");
        row(sb, "Config key", configKey == null || configKey.isBlank() ? "NOT AVAILABLE" : configKey);

        return sb.toString();
    }

    /**
     * Whose backups are in this repository, said to a person.
     *
     * <p>This page is the mapping. A repository is named after the machine's {@link MachineId}, so the
     * directory on the server is a UUID and tells an operator nothing — and this is the sheet they are
     * holding on the day there is no Vaier to ask. Normally the job that targets the repository names the
     * machine; when no job does, the repository's own <em>name</em> is the identity, so the machine can
     * still be named as one that has left the fleet rather than written off as "no machine".
     */
    private static String machineFor(BackupRepository repo, List<BackupJob> jobs,
                                     Map<MachineId, String> machineNames) {
        Optional<String> claimed = jobs == null ? Optional.empty() : jobs.stream()
            .filter(j -> repo.name().equals(j.repositoryName()))
            .map(j -> nameOf(j.machineId(), machineNames))
            .findFirst();
        return claimed.orElseGet(() -> nameOf(identityNamedBy(repo), machineNames));
    }

    /**
     * The repository's name read as a {@link MachineId}, or null when it is not one — a repository adopted
     * from before Vaier, or one created before repositories were named by identity.
     */
    private static MachineId identityNamedBy(BackupRepository repo) {
        try {
            return MachineId.of(repo.name());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * What to call a machine on this page. An id with no name is not hidden and not guessed — that rule is
     * {@link Machine#labelFor}'s, shared with the admin alerts, because the archives are still there to read
     * and an operator has to be able to tell a live machine from a departed one on every one of them.
     */
    private static String nameOf(MachineId machineId, Map<MachineId, String> machineNames) {
        // Never look a null id up: an immutable Map (Map.of()) throws on get(null), and this runs while
        // rendering the one page that has to survive everything else going wrong.
        String name = machineId == null || machineNames == null ? null : machineNames.get(machineId);
        return Machine.labelFor(machineId, Optional.ofNullable(name));
    }

    /** One aligned label/value line. Values are never wrapped — a broken passphrase is a useless passphrase. */
    private static void row(StringBuilder sb, String label, String value) {
        sb.append("  ").append(pad(label)).append("  ").append(value == null ? "" : value).append("\n");
    }

    private static String pad(String label) {
        StringBuilder padded = new StringBuilder(label);
        while (padded.length() < 12) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
