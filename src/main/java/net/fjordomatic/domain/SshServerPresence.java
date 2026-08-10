package net.fjordomatic.domain;

/**
 * Fjord's last-known belief about whether an SSH server is listening on a machine at all —
 * distinct from {@link MachineStatus}/{@link Reachability}, which answer "is the host on the
 * network" and know nothing about SSH specifically. A host can be perfectly reachable (it
 * answers pings, it serves a web UI) while carrying no SSH server, and the reverse never
 * happens: an SSH server implies the host itself is up.
 *
 * <p>{@link #UNKNOWN} — Fjord has never observed this machine's SSH port, or last observed it
 * ambiguously (a timeout, an auth failure, a host-key mismatch — none of which prove SSH is
 * absent, only that this particular attempt didn't land). {@link #PRESENT} — the most recent
 * SSH attempt reached and authenticated against a real SSH server. {@link #ABSENT} — the most
 * recent SSH attempt was refused outright ({@link NoSshServerException}): the machine answered
 * the TCP handshake itself and nothing was listening on the SSH port.
 *
 * <p>Recorded from the same 5-minute disk-usage sweep every SSH-accessible, credentialed
 * machine already receives ({@code RemoteDiskWatcher}) — no extra SSH traffic to the fleet —
 * and read back to gate the Explorer's SSH-dependent controls (the SSH-access checkbox, Open
 * shell, and the Files/Disk tree entries) so they grey out rather than fail after the click.
 * Self-healing: a later sweep that reaches the machine again reports {@link #PRESENT} and the
 * gate lifts on its own.
 */
public enum SshServerPresence {
    UNKNOWN,
    PRESENT,
    ABSENT
}
