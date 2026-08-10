package net.fjordomatic.domain;

/**
 * Fjord asked CrowdSec to lift a block and could not tell that it had (#329 Slice 3c).
 *
 * <p>The deliberate opposite of {@link net.fjordomatic.domain.port.ForDetectingIntrusions}'s contract, where a
 * failure reads as "no active decisions" so a transient outage cannot look like every ban clearing at once.
 * Reading can afford to go quiet; unblocking cannot. An operator who has just clicked "let this address
 * back in" is standing there waiting to be told whether it worked, and silence would read as success while
 * the address stayed banned.
 *
 * <p>Surfaces as {@code 502} — the failure is on the far side of Fjord, in the engine that owns the ban,
 * exactly like {@code DiskUnreadableException} and the other far-side refusals.
 */
public class BlockNotLiftedException extends RuntimeException {

    public BlockNotLiftedException(String message) {
        super(message);
    }

    public BlockNotLiftedException(String message, Throwable cause) {
        super(message, cause);
    }
}
