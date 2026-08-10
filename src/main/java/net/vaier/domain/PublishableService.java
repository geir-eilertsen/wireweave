package net.vaier.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A discovered service eligible for publishing. Its {@code source} indicates where it was
 * discovered (Vaier server, server peer, or Docker-enabled LAN server). Owns the publishing
 * identity rules ({@link #ignoreKey()}, {@link #suggestedSubdomain()}) so the frontend and the
 * ignored-services persistence layer never recompute them and drift apart.
 *
 * @param machineId the identity of the machine this service was discovered on. Null only when the machine
 *                  is in no registry — a live peer with no stored config — in which case there is nothing
 *                  to attribute it to and inventing an id would attribute it to nothing.
 */
public record PublishableService(
    PublishableSource source,
    String machineId,
    String peerId,
    String address,
    String containerName,
    int port,
    String rootRedirectPath,
    boolean ignored
) {
    public enum PublishableSource { VAIER_SERVER, PEER, LAN_SERVER }

    // Stable identity used by the ignored-services persistence layer. Lives on the entity so the
    // frontend doesn't recompute it from the source enum (and so the two can never drift apart).
    //
    // The peer part is the peer's ID — its WireGuard config directory — which is why this key survived
    // the identity refactor untouched: it never held a display name, whatever the field used to be
    // called. The VALUE must not change, or every service an operator has ignored comes back.
    @JsonProperty("ignoreKey")
    public String ignoreKey() {
        return switch (source) {
            case PEER         -> peerId + "/" + containerName + ":" + port;
            case LAN_SERVER   -> address  + "/" + containerName + ":" + port;
            case VAIER_SERVER -> containerName + ":" + port;
        };
    }

    /**
     * Whether this discovered service sits on the machine with {@code candidate}'s identity, so the
     * "publish me" nudge can be attributed per machine.
     *
     * <p>By identity, and not — as it was — by matching the owner's <em>name</em> against the machine's.
     * A name match answers "a machine called that", where the caller asked "this machine", and the two
     * differ the moment two machines share a name. A service whose machine is in no registry belongs to
     * nobody and matches nothing.
     */
    public boolean belongsTo(MachineId candidate) {
        return machineId != null && candidate != null && machineId.equals(candidate.value());
    }

    /**
     * Whether this service is still an <em>open question</em> on the machine with {@code candidate}'s
     * identity: it sits there, and the operator has not already dismissed it.
     *
     * <p>Ownership alone is not enough, which is what the publish nudge used to ask. The discovered feed
     * deliberately keeps ignored services in it — the Explorer folds them behind "Show ignored" rather than
     * dropping them, so dismissing stays undoable — and a count taken straight off that feed therefore
     * counted the operator's own "no" as a reason to ask again. Ignoring a service <b>is</b> the answer to
     * this nudge; re-asking it is how a nudge rail teaches you to stop reading it.
     */
    public boolean awaitsPublishingOn(MachineId candidate) {
        return !ignored && belongsTo(candidate);
    }

    /**
     * The subdomain the publish modal pre-fills. Vaier-server services get the container name
     * alone; peer- and LAN-hosted services get {@code containerName.peerId} so multiple peers
     * publishing the same container don't collide on one DNS label.
     */
    @JsonProperty("suggestedSubdomain")
    public String suggestedSubdomain() {
        return source == PublishableSource.VAIER_SERVER
            ? containerName
            : containerName + "." + peerId;
    }
}
