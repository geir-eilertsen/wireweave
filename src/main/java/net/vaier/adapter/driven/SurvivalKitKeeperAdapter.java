package net.vaier.adapter.driven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.CommandResult;
import net.vaier.domain.MachineId;
import net.vaier.domain.SurvivalKit;
import net.vaier.domain.port.ForKeepingSurvivalKits;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Puts a {@link SurvivalKit} where it will still be found: in the SSH user's home directory on a fleet
 * machine, and beside Vaier's own configuration on the Vaier server.
 *
 * <p><b>Why base64 over the wire.</b> A kit is prose, base64 and shell metacharacters all at once — it
 * contains quotes, a {@code sed} expression, and a line beginning with {@code $}. Any of those would be
 * mangled or, worse, interpreted by a heredoc or an echo. So the kit is base64-encoded on this side and
 * decoded on the far side: the only bytes that ever reach a shell are {@code [A-Za-z0-9+/=]}. A kit that
 * arrived subtly corrupted would decrypt to nothing on the one day it was needed, and nothing before then
 * would have said so.
 *
 * <p><b>Why the home directory.</b> Not {@code /var/lib} or anywhere else needing root: the SSH user always
 * owns their home, and a kit that could only be written by escalating would silently stop being written on
 * every host where escalation was not available.
 */
@Component
@Slf4j
public class SurvivalKitKeeperAdapter implements ForKeepingSurvivalKits {

    private final ForResolvingSshTargets targets;
    private final ForRunningSshCommands ssh;
    private final String configDir;

    public SurvivalKitKeeperAdapter(ForResolvingSshTargets targets, ForRunningSshCommands ssh,
                                    @Value("${VAIER_CONFIG_PATH:/vaier/config}") String configDir) {
        this.targets = targets;
        this.ssh = ssh;
        this.configDir = configDir;
    }

    @Override
    public void keepOn(MachineId machineId, String content) {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        // umask before the write, so the kit is never briefly world-readable between creation and chmod.
        String command = "umask 077 && printf %s '" + encoded + "' | base64 -d > \"$HOME/"
            + SurvivalKit.FILE_NAME + "\"";

        CommandResult result = ssh.run(targets.resolve(machineId), command);
        if (result.exitCode() != 0) {
            // Thrown, never logged-and-shrugged: the rollout counts copies, and a silent failure here would
            // inflate that count until an operator believed they had three kits and owned one. The machine is
            // not named in the message — the rollout holds the name and puts it on the failure it reports.
            throw new IllegalStateException("could not write the survival kit: " + firstLine(result.stderr()));
        }
        log.info("Survival kit written on machine {}", machineId);
    }

    @Override
    public void keepOnTheVaierServer(String content) {
        Path file = Path.of(configDir, SurvivalKit.FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            SecureFilePermissions.lockDownFile(file);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the survival kit to " + file + ": "
                + e.getMessage(), e);
        }
        log.info("Survival kit written on the Vaier server at {}", file);
    }

    /** The machine's own words about what went wrong, bounded — stderr can be long and is not a report. */
    private static String firstLine(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "the write failed with no message";
        }
        String line = stderr.strip().lines().findFirst().orElse("").strip();
        return line.length() > 200 ? line.substring(0, 200) : line;
    }
}
