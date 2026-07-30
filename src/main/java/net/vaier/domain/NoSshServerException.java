package net.vaier.domain;

import java.util.Locale;

/**
 * The machine is up and reachable — the connection was actively <b>refused</b>, not timed out — but
 * nothing is listening on its SSH port at all. Distinct from {@link SshConnectException}'s other causes
 * (a timeout, "no route to host") because those mean "the machine might be asleep or the network might be
 * down", where a refusal means the machine answered the TCP handshake itself and there is simply no
 * process behind that port: SSH was never installed, was stopped, or was disabled.
 *
 * <p>It exists so the operator is told the one thing they can act on, exactly as {@link
 * NoSftpSubsystemException} does for its own narrower case. Collapsed into {@link SshConnectException} it
 * reads as "Connection refused" — accurate, but it reads like a network fault, and sends the operator
 * looking for one when the real answer is "install and start an SSH server on this machine". The remedy is
 * carried in the message itself rather than added by whichever caller reports the failure: what to do about
 * a machine is knowledge about the machine, and a remedy assembled at the web edge would be silently absent
 * everywhere else the failure surfaces (the Explorer and the web terminal both reach a machine through the
 * one shared {@code SshConnector}).
 *
 * <p>Carries the machine so a handler can name it, exactly as {@link NoHostCredentialException} does.
 */
public class NoSshServerException extends RuntimeException {

    /**
     * What a refused TCP connect reports, distinct from a timeout ("Connection timed out") or an
     * unreachable network ("No route to host") — both of which mean the machine could simply be asleep or
     * behind a network fault that has nothing to do with SSH.
     */
    private static final String CONNECTION_REFUSED = "connection refused";

    private final String machineName;

    public NoSshServerException(String machineName, int port) {
        super("\"" + machineName + "\" does not answer SSH at all — nothing is listening on port " + port
            + ". Install and start an SSH server there (Dropbear or OpenSSH) if this machine should be "
            + "reachable.");
        this.machineName = machineName;
    }

    /**
     * Whether a connect failure whose root message is {@code rootMessage} means no SSH server is listening
     * on this machine at all, rather than the machine being asleep, unreachable, or slow to answer.
     *
     * <p>The decision is deliberately narrow, exactly as {@link NoSftpSubsystemException#isSubsystemRefusal}
     * is: a refusal is a distinct, well-defined TCP outcome (the kernel sent back an RST because nothing was
     * listening) — never a timeout, never a routing failure, never a credential problem — so recognising it
     * carries none of the false-positive risk a fuzzier signal would.
     */
    public static boolean isNoServerListening(String rootMessage) {
        if (rootMessage == null || rootMessage.isBlank()) {
            return false;
        }
        return rootMessage.toLowerCase(Locale.ROOT).contains(CONNECTION_REFUSED);
    }

    /** The machine with no SSH server listening — carried separately so a handler can name it. */
    public String machineName() {
        return machineName;
    }
}
