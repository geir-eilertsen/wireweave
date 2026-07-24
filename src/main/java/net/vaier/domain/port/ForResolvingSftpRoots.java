package net.vaier.domain.port;

import net.vaier.domain.SftpRoot;
import net.vaier.domain.SshTarget;

/**
 * Driven port for learning where a machine's SFTP subsystem believes the filesystem begins — its
 * {@link SftpRoot} (#326).
 *
 * <p>The answer cannot be read off a machine's configuration; it has to be asked for, down both channels at
 * once, and the asking costs two SSH connections. So it sits behind a port: the Explorer states what it needs
 * ("where does this machine's file tree begin?") and the adapter is free to remember the answer, which is the
 * only reason browsing a jailed machine does not pay for the probes on every directory click.
 *
 * <p>A machine that cannot be probed resolves to {@link SftpRoot#NONE} — never an exception, and never a
 * guess. Not knowing where a jail is leaves every path exactly as it was, which is the safe outcome; inventing
 * a prefix would silently corrupt every path on the machine, in both directions.
 */
public interface ForResolvingSftpRoots {

    /**
     * Where the file tree of the machine reachable at {@code target} begins.
     *
     * <p>The answer is remembered under the target's own {@link net.vaier.domain.MachineId} — its identity,
     * which is what the machine <em>is</em>. {@code machineName} is only what to call it in the log line
     * that announces a jail, and carries no meaning to the implementation beyond that: a name is a label an
     * operator edits, and two machines can wear the same one at different times.
     */
    SftpRoot rootFor(String machineName, SshTarget target);
}
