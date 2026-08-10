package net.fjordomatic.domain;

import java.util.Locale;

/**
 * The machine answers SSH, accepts the login — and has no <b>SFTP subsystem</b> behind it, so no file of
 * its can ever be read. Not a transport failure and not a refusal of this path: a fact about how that
 * machine's SSH server was built. Dropbear, which DietPi ships, serves SFTP only when an external
 * {@code sftp-server} binary is present, and a minimal install has none — while its shell, its {@code df}
 * and its Docker scrape all keep working, so the machine looks entirely healthy from everywhere else.
 *
 * <p>It exists so the operator is told the one thing they can act on. Collapsed into
 * {@link SshConnectException} it reads as "Closing while await init message" — a MINA transport detail
 * that sends someone hunting a network fault that is not there. The answer is a package to install, or a
 * switch to OpenSSH, and it is carried in the message itself rather than added by whichever adapter
 * reports the failure: what to do about a machine is knowledge about the machine, and a remedy assembled
 * at the web edge would be silently absent everywhere else the failure surfaces.
 *
 * <p>Carries the machine so a handler can name it, exactly as {@link NoHostCredentialException} does.
 * Callers pass whatever names the machine to the operator; the SFTP adapter passes its SSH host, because
 * {@link SshTarget} carries no display name and inventing a lookup to get a prettier one would reach
 * across a boundary for cosmetics.
 */
public class NoSftpSubsystemException extends RuntimeException {

    /**
     * What MINA says when the SFTP channel is closed under it while it waits for the subsystem's version
     * packet — the shape a refused or absent subsystem takes on the client side, because the far end
     * accepts the channel and then has nothing to put behind it.
     *
     * <p>MINA has carried this text both bare and prefixed with the channel it happened on, hence a
     * substring match rather than an equality one.
     */
    private static final String CHANNEL_DIED_DURING_INIT = "closing while await init message";

    private final String machineName;

    public NoSftpSubsystemException(String machineName) {
        super("\"" + machineName + "\" answers SSH but does not offer SFTP — its SSH server has no SFTP "
            + "subsystem. Install openssh-sftp-server on it, or switch it to OpenSSH.");
        this.machineName = machineName;
    }

    /**
     * Whether an SSH failure whose root message is {@code rootMessage} means this machine has no SFTP
     * subsystem, rather than being unreachable, asleep, or refusing the login.
     *
     * <p>The decision is deliberately narrow: only signatures that <em>can only</em> arise from the
     * subsystem itself failing to come up are recognised. Anything else — a refused connection, a
     * timeout, a rejected credential — stays an ordinary transport failure, because telling an operator
     * to install a package on a machine that is merely asleep is worse than saying nothing precise at all.
     *
     * <p>Lives here rather than in the SFTP adapter because it is a judgement about what a machine
     * <em>is</em>, not a translation of a protocol — and because a second adapter reaching a machine's
     * files must reach the same verdict rather than re-invent it.
     */
    public static boolean isSubsystemRefusal(String rootMessage) {
        if (rootMessage == null || rootMessage.isBlank()) {
            return false;
        }
        return rootMessage.toLowerCase(Locale.ROOT).contains(CHANNEL_DIED_DURING_INIT);
    }

    /** The machine with no SFTP subsystem — carried separately so a handler can name it in {@code detail}. */
    public String machineName() {
        return machineName;
    }
}
