package net.vaier.domain;

import lombok.Builder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * One operator secret that must exist identically <em>on</em> every machine in the fleet that runs a
 * shell — the mirror image of a {@link HostCredential}, which is a secret Vaier uses to <em>reach</em> a
 * machine. It is a {@code name}, a {@code targetPath} to deliver to, the file {@code mode} to land it at,
 * the {@code content} itself, and whether the operator has ever {@code distributed} it.
 *
 * <p><b>Vaier is opaque about what the secret is.</b> It distributes bytes to a path and verifies that the
 * bytes arrived. Nothing here knows, or may ever learn, what any particular credential means — the moment
 * it did, the feature would stop being one feature and start being one per secret.
 *
 * <p>The content lives here in the clear; sealing it is a persistence concern the adapter applies on the
 * way to disk, exactly as {@link HostCredential} does. Whether a value is safe to expose is a domain
 * decision, and {@link #toView()} is the only shape allowed to leave the process. {@link #toString()} is
 * overridden for the same reason: a record's generated one would print the secret into the first log line
 * that ever mentioned a credential.
 *
 * <p>This type also owns the shell Vaier speaks to deliver, check and remove the file. Three rules earned
 * their place there rather than in an adapter:
 * <ul>
 *   <li><b>Every path is single-quoted</b> ({@link #shellPath()}), which {@code BorgCommand} learned the
 *       hard way — with the leading {@code ~} expanded to a double-quoted {@code "$HOME"}, because a
 *       single-quoted tilde is a literal tilde and the file would land in a directory called {@code ~}.</li>
 *   <li><b>The content travels base64-encoded</b>, so arbitrary bytes — newlines, quotes, anything —
 *       survive the shell intact, and the secret never appears in the command line, a shell history, or a
 *       process list.</li>
 *   <li><b>The write is never trusted.</b> Every command ends by reporting the file's actual owner, mode
 *       and digest back, and {@link #readWriteOutcome(String)} calls a mismatch a failure. The JVM runs as
 *       uid 1000 and a root-owned {@code 0600} file is silently unreadable rather than loudly broken: a
 *       push that lands as the wrong user produces a credential that exists, looks right, and does not
 *       work.</li>
 * </ul>
 */
@Builder(toBuilder = true)
public record FleetCredential(String name, String targetPath, String mode, String content,
                              boolean distributed) {

    /**
     * The identifier charset a fleet credential's {@code name} is confined to. The name addresses the
     * credential in a URL path segment and identifies it in the store, so anything outside
     * {@code [A-Za-z0-9_-]} is rejected at construction — the same rule, for the same reason, as
     * {@link BackupRepository} and {@link PeerId}, and deliberately not shared with either, since the
     * three concepts are unrelated.
     */
    private static final String NAME_PATTERN = "[A-Za-z0-9_-]+";

    /**
     * The safe charset for the delivery path. It legitimately holds {@code /}, {@code .}, {@code _} and
     * {@code -}, but must be free of spaces and every shell metacharacter, since it is interpolated into
     * a command line. A leading {@code ~} is handled separately and is the only place a tilde may appear.
     */
    private static final String PATH_PATTERN = "[A-Za-z0-9._/-]+";

    /** Three or four octal digits. */
    private static final String MODE_PATTERN = "[0-7]{3,4}";

    /**
     * The mode a fleet credential lands at unless the operator says otherwise. Owner-only: a secret that
     * every login on the machine can read has not been distributed so much as published.
     */
    public static final String DEFAULT_MODE = "0600";

    /**
     * The line every rendered command echoes back. Deliberately a string no ordinary command output
     * contains, so a machine's MOTD, a shell warning or a {@code stat} error can never be misread as a
     * report.
     */
    public static final String REPORT_MARKER = "VAIER-FLEET-CREDENTIAL";

    public FleetCredential {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Fleet credential name must not be blank");
        }
        if (!name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException(
                "Fleet credential name must contain only [A-Za-z0-9_-]: " + name);
        }
        requireSafePath(targetPath);
        if (mode == null || !mode.matches(MODE_PATTERN)) {
            throw new IllegalArgumentException(
                "Fleet credential mode must be three or four octal digits: " + mode);
        }
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("Fleet credential content must not be blank");
        }
    }

    /**
     * A fleet credential the operator has just described, not yet distributed anywhere. A blank
     * {@code mode} means {@link #DEFAULT_MODE} — which mode a secret defaults to is a decision about
     * secrets, so it is taken here rather than in a controller or a form.
     */
    public static FleetCredential of(String name, String targetPath, String mode, String content) {
        return new FleetCredential(name, targetPath,
            mode == null || mode.isBlank() ? DEFAULT_MODE : mode, content, false);
    }

    /**
     * This credential, keeping the distribution standing of the one it replaces ({@code previous}, empty
     * when it replaces nothing). Editing a live credential's path, mode or content must not quietly take
     * it off the reconcile — the fleet would drift from the moment of the edit with nothing saying so —
     * and a fresh credential must not inherit a standing it never earned.
     */
    public FleetCredential carryingStandingFrom(Optional<FleetCredential> previous) {
        return previous.map(FleetCredential::distributed).orElse(false)
            ? markDistributed() : markWithdrawn();
    }

    /** This credential, now that the operator has pushed it to the fleet at least once. */
    public FleetCredential markDistributed() {
        return new FleetCredential(name, targetPath, mode, content, true);
    }

    /** This credential, now that the operator has revoked it from the fleet. */
    public FleetCredential markWithdrawn() {
        return new FleetCredential(name, targetPath, mode, content, false);
    }

    /**
     * Whether the background sweep may heal this credential onto a machine that is missing it. Only a
     * credential the operator has already distributed by hand qualifies: a secret that has never been
     * pushed must never reach a machine because a timer fired.
     */
    public boolean shouldReconcile() {
        return distributed;
    }

    /** The redacted view — carries no content bytes and no digest. */
    public FleetCredentialView toView() {
        return new FleetCredentialView(name, targetPath, mode, content != null && !content.isEmpty(),
            distributed);
    }

    /**
     * The SHA-256 of the content, lower-case hex — the same digest {@code sha256sum} prints on the host,
     * which is what lets Vaier ask "is this machine current?" without ever shipping the secret to compare.
     */
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * The delivery path as a shell word. An absolute path is single-quoted whole. A {@code ~/}-relative
     * one becomes {@code "$HOME"'/rest/of/path'} — the tilde expanded by the remote shell, everything the
     * operator typed still inside single quotes where it cannot break out.
     */
    public String shellPath() {
        if (targetPath.startsWith("~/")) {
            return "\"$HOME\"" + singleQuote(targetPath.substring(1));
        }
        return singleQuote(targetPath);
    }

    /**
     * Deliver the content and report what actually landed. The content arrives base64-encoded so no byte
     * in it can touch the shell; {@code umask 077} means the file is never briefly world-readable, even
     * before the {@code chmod}. The report runs unconditionally — a write that failed must still say what
     * is on disk, rather than leaving the caller to infer it from an exit code.
     */
    public String writeCommand() {
        String encoded = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        return pathAssignment()
            + "umask 077; mkdir -p \"$(dirname \"$P\")\" && printf %s " + singleQuote(encoded)
            + " | base64 -d > \"$P\" && chmod " + mode + " \"$P\"; "
            + report();
    }

    /** Report what is on the machine — owner, mode and digest — without shipping a single secret byte. */
    public String verifyCommand() {
        return pathAssignment() + report();
    }

    /** Remove the file, then confirm from the machine itself that it is gone. */
    public String removeCommand() {
        return pathAssignment() + "rm -f \"$P\"; " + report();
    }

    /**
     * Read a {@link #verifyCommand()} report as a state. {@code CURRENT} only when the file is there,
     * byte-identical, owned by the login user and at this credential's mode; a drift in any of those is
     * {@code STALE} — something the reconcile may heal — and a report Vaier cannot parse at all is
     * {@code FAILED}, never quietly a success.
     */
    public FleetCredentialState readVerification(String stdout) {
        Map<String, String> report = parseReport(stdout);
        if (report.isEmpty()) {
            return FleetCredentialState.FAILED;
        }
        if ("absent".equals(report.get("state"))) {
            return FleetCredentialState.MISSING;
        }
        if (!"present".equals(report.get("state"))) {
            return FleetCredentialState.FAILED;
        }
        return matchesExactly(report) ? FleetCredentialState.CURRENT : FleetCredentialState.STALE;
    }

    /**
     * Read a {@link #writeCommand()} report as a state: {@code CURRENT} when the file on disk matches in
     * every respect, {@code FAILED} otherwise. Nothing in between — a write is the one moment Vaier knows
     * exactly what should be there, so anything else is a failure to report, not a drift to heal later.
     * A wrong owner is the case this exists for: uid-1000 Vaier writing a root-owned file produces a
     * credential that exists, looks right, and cannot be read by whoever needs it.
     */
    public FleetCredentialState readWriteOutcome(String stdout) {
        Map<String, String> report = parseReport(stdout);
        if (report.isEmpty() || !"present".equals(report.get("state")) || !matchesExactly(report)) {
            return FleetCredentialState.FAILED;
        }
        return FleetCredentialState.CURRENT;
    }

    /**
     * Read a {@link #removeCommand()} report: {@code WITHDRAWN} only on a confirmed absence. A file still
     * sitting there after an {@code rm} is a revocation that did not happen, and must say so.
     */
    public FleetCredentialState readWithdrawal(String stdout) {
        Map<String, String> report = parseReport(stdout);
        if ("absent".equals(report.get("state"))) {
            return FleetCredentialState.WITHDRAWN;
        }
        return FleetCredentialState.FAILED;
    }

    /** Whether a present-file report is this credential, exactly, owned by the login user. */
    private boolean matchesExactly(Map<String, String> report) {
        String owner = report.get("owner");
        String user = report.get("user");
        return owner != null && !owner.isBlank() && owner.equals(user)
            && normalizeMode(report.get("mode")).equals(normalizeMode(mode))
            && digest().equals(report.get("digest"));
    }

    /** {@code P=<quoted path>; } — assigned once so every later use is a plain, safe {@code "$P"}. */
    private String pathAssignment() {
        return "P=" + shellPath() + "; ";
    }

    /**
     * The shared tail of every command: who we are logged in as, and — when the file exists — its owner,
     * mode and digest, on one marker line. GNU {@code stat}/{@code sha256sum} with a BSD/Synology
     * fallback, so a NAS answers the same question a Linux server does.
     */
    private static String report() {
        return "U=$(id -un); if [ -f \"$P\" ]; then "
            + "O=$(stat -c %U \"$P\" 2>/dev/null || stat -f %Su \"$P\" 2>/dev/null); "
            + "M=$(stat -c %a \"$P\" 2>/dev/null || stat -f %Lp \"$P\" 2>/dev/null); "
            + "D=$({ sha256sum \"$P\" 2>/dev/null || shasum -a 256 \"$P\" 2>/dev/null; } "
            + "| cut -d' ' -f1); "
            + "echo \"" + REPORT_MARKER + " state=present user=$U owner=$O mode=$M digest=$D\"; "
            + "else echo \"" + REPORT_MARKER + " state=absent user=$U\"; fi";
    }

    /**
     * The {@code key=value} pairs off the marker line, or empty when the output carries none. Never
     * throws: a machine that answered with an MOTD, a broken shell or nothing at all is a failure to
     * report, not an exception to propagate through a fleet-wide sweep.
     */
    private static Map<String, String> parseReport(String stdout) {
        Map<String, String> fields = new HashMap<>();
        if (stdout == null) {
            return fields;
        }
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith(REPORT_MARKER)) {
                continue;
            }
            for (String token : trimmed.substring(REPORT_MARKER.length()).strip().split("\\s+")) {
                int eq = token.indexOf('=');
                if (eq > 0) {
                    fields.put(token.substring(0, eq), token.substring(eq + 1));
                }
            }
            return fields;
        }
        return fields;
    }

    /**
     * {@code 0600} and {@code 600} are the same mode — the operator types the leading zero and
     * {@code stat} does not print it — so they are compared in one canonical form.
     */
    private static String normalizeMode(String value) {
        if (value == null) {
            return "";
        }
        return value.length() == 4 && value.startsWith("0") ? value.substring(1) : value;
    }

    /**
     * A delivery path must be absolute or {@code ~/}-relative, and made only of safe characters. A
     * relative path would land wherever the login shell happened to start; a {@code ..} would land the
     * secret somewhere other than where the operator is looking.
     */
    private static void requireSafePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Fleet credential targetPath must not be blank");
        }
        String withoutTilde = path.startsWith("~/") ? path.substring(1) : path;
        if (!withoutTilde.startsWith("/")) {
            throw new IllegalArgumentException(
                "Fleet credential targetPath must be absolute or ~/-relative: " + path);
        }
        if (!withoutTilde.matches(PATH_PATTERN)) {
            throw new IllegalArgumentException(
                "Fleet credential targetPath must contain only [A-Za-z0-9._/-]: " + path);
        }
        for (String segment : withoutTilde.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                    "Fleet credential targetPath must not traverse upwards: " + path);
            }
        }
    }

    /**
     * Wrap {@code value} in single quotes for the shell, escaping any embedded single quote with the
     * {@code '\''} idiom so the value cannot break out of its quoting.
     */
    private static String singleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Redacted on purpose — a record's generated {@code toString} would print the secret. */
    @Override
    public String toString() {
        return "FleetCredential[name=" + name + ", targetPath=" + targetPath + ", mode=" + mode
            + ", distributed=" + distributed + "]";
    }
}
