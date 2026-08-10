package net.fjordomatic.application;

import java.time.Instant;

/**
 * Record that Fjord's forward-auth check let one request through, from this caller IP, by this person.
 *
 * <p>Invoked from inside the authorization check for <em>every</em> request to <em>every</em> gated
 * service — a published service, the console, the Explorer, Settings. That is the whole contract: it must
 * be fast, it must never touch the disk (the flush is what the disk is for), and it must never throw. A
 * broken map is a lost statistic; an exception here would be an operator locked out of their own services.
 */
public interface RecordAllowedAccessUseCase {

    void recordAllowedAccess(String callerIp, String person, Instant at);
}
