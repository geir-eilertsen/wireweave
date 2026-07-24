package net.vaier.application;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.CommandResult;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.SshHome;
import net.vaier.domain.SshTarget;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the on-host directory a machine's borg runs use for their pass file and per-run
 * {@code .rc}/{@code .log} state. Vaier runs borg as the SSH user in {@code host-credentials.yml} (e.g.
 * {@code geir}, not root), who cannot create a directory under {@code /var/lib}; so the work dir is
 * {@code <home>/.vaier-backup}, resolved from the target host's {@code $HOME} over SSH, falling back to
 * {@code /tmp/vaier-backup} (always writable by any user) when resolution fails.
 *
 * <p><b>Why the absolute path is resolved here, not left as {@code $HOME} in the borg command.</b> The
 * run reads its passphrase via {@code BORG_PASSCOMMAND="cat <workDir>/<repo>.pass"}, and borg executes a
 * {@code BORG_PASSCOMMAND} <em>without a shell</em>. A literal {@code $HOME} embedded in that passcommand
 * would therefore never expand — borg would try to {@code cat} a file literally named {@code $HOME/...}
 * and fail. The path Vaier writes the pass file to and the path borg reads it from must be the exact same
 * absolute string, so {@code $HOME} is expanded once, here in the orchestration, before it ever reaches a
 * borg-consumed command. This keeps {@link net.vaier.domain.BorgCommand}'s command strings literal
 * absolute paths with no shell-quoting or expansion concerns.
 *
 * <p>A resolved home is cached per machine (the home does not move). A fallback is deliberately
 * <em>not</em> cached, so a transient probe failure never poisons the cache — a later call can still
 * resolve the real home once the host is reachable again. Resolution never throws.
 */
@Component
@Slf4j
public class BackupWorkDirResolver {

    /** Always writable by any SSH user; used when {@code $HOME} cannot be resolved. */
    static final String FALLBACK_WORK_DIR = "/tmp/vaier-backup";

    /**
     * The one {@code $HOME} probe, owned by the domain ({@link SshHome}) rather than spelled out here.
     *
     * <p>The Explorer asks a machine the same question, to work out where its SFTP subsystem is rooted (#326).
     * Two spellings of one probe would be two ways of reaching a host that could quietly drift apart — and
     * Vaier has been bitten before by code paths that disagreed about a machine.
     */
    static final String HOME_PROBE = SshHome.PROBE_COMMAND;

    private final RunRemoteCommandUseCase remoteCommand;

    /**
     * {@link MachineId} -> the resolved absolute {@code $HOME}. The home is the primitive; the work dir derives.
     *
     * <p>Keyed by identity, not by name. A cache keyed on a display name answers for whatever machine now
     * bears that name — so renaming one machine into another's old name would have served a stale home, and
     * the home is the absolute path a run's pass file and its {@code BORG_RSH} key literals are built from.
     */
    private final Map<MachineId, String> homeCache = new ConcurrentHashMap<>();

    public BackupWorkDirResolver(RunRemoteCommandUseCase remoteCommand) {
        this.remoteCommand = remoteCommand;
    }

    /**
     * The work dir for a machine: a cached {@code <home>/.vaier-backup} once resolved, else a
     * fresh {@code $HOME} probe over SSH. Returns {@link #FALLBACK_WORK_DIR} (uncached) on any failure —
     * a timeout, a non-zero exit, a blank or non-absolute {@code $HOME}, or an SSH error — so a run is
     * never blocked and a blip never sticks.
     *
     * <p>The machine arrives as both its {@code machineId} (which keys the cache) and its {@code machineName}
     * (which is still how the SSH command path addresses a host). The two must describe one machine; every
     * caller takes them from a single resolved {@link Machine} or {@link SshTarget}, never from two lookups.
     * The name parameter goes away when the SSH path itself becomes id-native.
     *
     * <p>There is deliberately no {@code workDirFor(Machine)} convenience overload. Two ways to ask the same
     * question is what this refactor is removing, and a test that mocks this class would silently get {@code
     * null} from whichever overload it did not stub.
     */
    public String workDirFor(MachineId machineId, String machineName) {
        return homeFor(machineId, machineName).map(home -> home + "/.vaier-backup").orElse(FALLBACK_WORK_DIR);
    }

    /**
     * The SSH user's absolute {@code $HOME} on a machine, cached once resolved — the same probe and
     * cache {@link #workDirFor} derives its directory from.
     *
     * <p>This is what a <b>Back up as root</b> run is built from. The borg client key and the pinned
     * backup-server host key both live in the SSH <em>user's</em> home, and under sudo ssh runs as root — which
     * would read {@code /root/.ssh/}, where neither exists. Setting {@code HOME} does <b>not</b> fix that:
     * OpenSSH ignores {@code $HOME} and resolves {@code ~} from the running UID's passwd entry. So the run names
     * both files as absolute literals under this home, via {@code BORG_RSH} (see {@code BorgCommand.borgBinary});
     * {@code HOME} is passed too, for the tools that do honour it. As with the work dir, the home is expanded
     * here, once, into an absolute literal — a {@code $HOME} left in the command would be reset by sudo before
     * borg ever saw it.
     *
     * <p>Unlike {@link #workDirFor} there is <b>no fallback</b>: an unresolvable home comes back empty rather
     * than guessed. A missing home does not degrade an as-root run, it breaks it — the run cannot even name the
     * key and the host pin — so the orchestration must refuse the run instead. Never throws.
     */
    public java.util.Optional<String> homeFor(MachineId machineId, String machineName) {
        String cached = homeCache.get(machineId);
        if (cached != null) {
            return java.util.Optional.of(cached);
        }
        try {
            CommandResult result = remoteCommand.run(machineName, HOME_PROBE);
            // What counts as a usable $HOME is the domain's rule, held once on SshHome: a probe that timed
            // out, exited non-zero, or answered blank or relative has told Vaier nothing about this machine.
            java.util.Optional<String> home = SshHome.in(result);
            if (home.isPresent()) {
                homeCache.put(machineId, home.get());
                return home;
            }
        } catch (Exception e) {
            log.debug("Could not resolve $HOME on {} for backup work dir: {}",
                forLog(machineName), e.getMessage());
        }
        // Deliberately NOT cached: a transient probe failure must never poison the cache.
        return java.util.Optional.empty();
    }

    /**
     * Render a machine name safe for a single log line: collapse CR/LF and other ISO control characters to
     * spaces so a hand-edited name can never forge a multiline log entry. Mirrors the {@code rest/}-layer
     * {@code LogSafe}, kept local so this application component carries no dependency into the web layer.
     */
    private static String forLog(String name) {
        if (name == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(name.length());
        name.codePoints().forEach(c -> sb.appendCodePoint(Character.isISOControl(c) ? ' ' : c));
        return sb.toString();
    }
}
