package net.vaier.domain.port;

import net.vaier.domain.MachineId;

/**
 * The Vaier server's own {@link MachineId} — the one machine identity that cannot be found by searching,
 * because the Vaier server is neither a VPN peer nor a LAN server and appears in no machine store. It lives
 * in the Vaier config, and this port is the only way to ask for it.
 *
 * <p><b>Why a port rather than each caller reading the config.</b> The question has two halves that must be
 * answered together: <em>read</em> the stored id, and — only the first time, when there is none yet —
 * <em>assign</em> one and persist it. Split across callers, those halves drift: a read-only caller resolving
 * the Vaier server before any assigning caller had run got no identity at all, and the machine it was trying
 * to reach came back as not found. One implementation, so every caller sees the same answer regardless of
 * which of them asks first.
 *
 * <p>Assigning is deliberately confined here and happens <b>once</b>. Everywhere else in Vaier identity is
 * <em>read, never minted</em>: a stored id that is missing or malformed must not quietly become a different
 * machine, because everything keyed to the old one would be orphaned in silence. The Vaier server is the one
 * exception, and only because it has no other way to acquire an identity — it is not adopted or created by
 * an operator the way a peer or LAN server is.
 */
public interface ForResolvingVaierServerIdentity {

    /**
     * The Vaier server's identity, assigning and persisting one if it has never had it. Never null: a caller
     * that has reached this point needs an identity to key something by, and there is no useful answer
     * short of one.
     */
    MachineId identity();
}
