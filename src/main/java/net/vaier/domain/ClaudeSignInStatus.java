package net.vaier.domain;

/**
 * Where one <em>user on</em> one machine stands on {@link ClaudeSignIn Claude sign-in}: the machine's
 * identity and display name, the {@link EffectiveUser} the answer is about, the
 * {@link ClaudeSignInState} Vaier last read, and — when it is signed in and said so — the
 * {@link ClaudeAccount} it is signed in as. Null account whenever there is no account to name.
 *
 * <p><b>The user is half of what this identifies, not decoration on it.</b> A Claude sign-in lives in one
 * user's home directory, so it is a fact about a user on a machine and never about the machine. Vaier
 * asks as its {@link EffectiveUser} — the login in that machine's {@link HostCredential} — and that is
 * frequently not the account the machine's real work runs as. Live, Colina 27 reported "signed in, max"
 * for {@code geir} while the operator's automation, running as {@code root} on the same box, was signed
 * out and expired. Both readings were true; the status that named only the machine was the lie. So every
 * standing carries its user, and every sentence built from one has to say whose sign-in it describes.
 *
 * <p>Keyed by {@link MachineId}, not by name: a rename must never move one machine's standing onto
 * another. The name rides along purely so the browser has something to print.
 *
 * <p><b>It carries no credential material, and could not.</b> Every field here is read from
 * {@code claude auth status --json}, whose output contains no token, key or session — which is exactly
 * why Vaier asks the CLI this question rather than inferring the answer from the credential file the CLI
 * keeps. The account rides along as an observation and is never stored: a fleet quietly signed into the
 * wrong account looks identical to a healthy one until something fails.
 */
public record ClaudeSignInStatus(MachineId machineId, String machineName, EffectiveUser effectiveUser,
                                 ClaudeSignInState state, ClaudeAccount account) {

    /**
     * Reads one machine's answer to {@link ClaudeSignIn#statusCommand()} as the standing of
     * {@code effectiveUser} on it — the user Vaier asked as, and so the only user the answer is about.
     */
    public static ClaudeSignInStatus read(MachineId machineId, String machineName,
                                          EffectiveUser effectiveUser, String stdout) {
        return new ClaudeSignInStatus(machineId, machineName, effectiveUser,
            ClaudeSignIn.readStatus(stdout), ClaudeSignIn.readAccount(stdout).orElse(null));
    }

    /**
     * A machine Vaier cannot open a shell on, so not a place a sign-in could ever happen. Its
     * {@code effectiveUser} is null when the reason is that Vaier holds no login for it — there is then no
     * user to name, and naming one would be inventing it.
     */
    public static ClaudeSignInStatus skipped(MachineId machineId, String machineName,
                                             EffectiveUser effectiveUser) {
        return new ClaudeSignInStatus(machineId, machineName, effectiveUser,
            ClaudeSignInState.SKIPPED, null);
    }

    /** A machine that did not answer. Silence is never read as signed in. */
    public static ClaudeSignInStatus unreachable(MachineId machineId, String machineName,
                                                 EffectiveUser effectiveUser) {
        return new ClaudeSignInStatus(machineId, machineName, effectiveUser,
            ClaudeSignInState.UNREACHABLE, null);
    }

    /**
     * Whether a sign-in could begin here — the same rule {@link ClaudeSignIn#requireSignInCanBegin}
     * enforces, answered rather than left to be inferred from {@link #state()}.
     *
     * <p><b>An already-signed-in machine can sign in again</b>, which is how an operator moves one onto a
     * different account or replaces a credential that has gone bad. The browser previously worked this out
     * for itself from the state name and got precisely that case wrong — a signed-in machine was offered no
     * way in. Two copies of one rule is two chances to disagree, and they did.
     */
    public boolean signInCanBegin() {
        return state != ClaudeSignInState.NOT_INSTALLED && state != ClaudeSignInState.SKIPPED;
    }

    /**
     * Whether this machine is a place a sign-in could happen at all — i.e. whether Vaier can reach a shell
     * on it. False only for {@link ClaudeSignInState#SKIPPED}, which is the answer for a machine with no
     * SSH access or no stored login.
     *
     * <p>It exists so the browser does not re-derive {@code Machine.runsAShellVaierCanReach} from an
     * {@code sshAccess &&  hasCredential} pair of its own. A section explaining why a sign-in is impossible
     * is worth less than no section, so this is what decides whether one is drawn.
     */
    public boolean signInIsPossibleHere() {
        return state != ClaudeSignInState.SKIPPED;
    }

    /** The login name this standing is about, or null when Vaier holds no login for the machine. */
    public String effectiveUsername() {
        return effectiveUser == null ? null : effectiveUser.username();
    }
}
