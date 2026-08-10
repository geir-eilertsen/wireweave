package net.vaier.domain;

/**
 * The user Vaier acts as on a machine — the login name in that machine's {@link HostCredential} — and
 * whether that user is privileged. It is the machine's blast radius, said out loud: every file Vaier
 * lists, downloads, writes or <em>deletes</em> over SFTP, and every {@code borg} read a backup takes,
 * happens as this user and can reach exactly what this user can reach.
 *
 * <p>The fleet is not uniform about it. A DietPi box arrives logging in as {@code root} by default, so
 * Vaier's delete there can remove something the machine needs to boot; an Ubuntu server logs in as an
 * ordinary account that cannot even read most of the filesystem, so its backups silently skip whatever
 * belongs to somebody else. Same tree, same buttons, two completely different consequences — and until
 * this value existed nothing in the UI said which one you were standing on.
 *
 * <p><b>What the judgement is, and what it is not.</b> Vaier judges by the login name it connects with,
 * because that name is what fixes the blast radius of everything Vaier does over SFTP on that machine.
 * It does <em>not</em> claim to detect a uid-0 alias under another name (a second account with
 * {@code uid 0} reads as unprivileged here), and it does not claim anything about {@code sudo}: a
 * non-root user with passwordless sudo is still <b>unprivileged</b> for Vaier's own file operations,
 * which never go through sudo. That is the honest answer to the question actually being asked, and
 * establishing it costs no SSH round trip — the username is already stored.
 *
 * <p>The predicate lives here and nowhere else. No service, controller or browser re-derives privilege
 * by comparing a string to {@code "root"}; they receive {@link #privileged()} already decided.
 */
public record EffectiveUser(String username, boolean privileged) {

    /** The one login name Vaier treats as privileged. */
    private static final String ROOT = "root";

    /**
     * The effective user for a login name, deciding privilege from the name itself — or {@code null}
     * when there is no login name at all, which is what "Vaier holds no credential here" looks like.
     * Surrounding whitespace is never part of a login name and is trimmed; case is significant, because
     * on Linux {@code Root} is a different account and Vaier must not claim it is uid 0.
     */
    public static EffectiveUser of(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String login = username.trim();
        return new EffectiveUser(login, ROOT.equals(login));
    }
}
