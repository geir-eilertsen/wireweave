package net.vaier.application;

import java.time.Instant;

/**
 * Record that Vaier's forward-auth check let one request through, from this caller IP, by this person.
 *
 * <p>Invoked from inside the authorization check for <em>every</em> request to <em>every</em> gated
 * service — a published service, the console, the Explorer, Settings. That is the whole contract: it must
 * be fast, it must never touch the disk (the flush is what the disk is for), and it must never throw. A
 * broken map is a lost statistic; an exception here would be an operator locked out of their own services.
 */
public interface RecordAllowedAccessUseCase {

    /**
     * @param callerIp the address the request really came from — {@code domain.CallerIp}'s decision, never
     *                 a raw header. It is also the only thing that can name a <em>device</em>: on the
     *                 tunnel it is a peer's own address, and off it, nothing.
     * @param person   the identity the forward-auth check let through.
     * @param host     the gated host that was reached, from {@code X-Forwarded-Host}. This check is the
     *                 only place in Vaier that sees it.
     * @param at       when the request was let through.
     */
    void recordAllowedAccess(String callerIp, String person, String host, Instant at);
}
