package net.fjordomatic.domain;

import java.util.Optional;

/**
 * How an {@link ContainerUpdate update} ended, and what the operator is told about it.
 *
 * <p>The two compose runs are read separately on purpose. A pull that fails and a recreate that fails are
 * <b>different events with different consequences</b>: neither changed the running container, but only one
 * of them means a newer image is now sitting on the host unused. Above all, a failed recreate leaves the
 * old container running — which is the <em>good</em> outcome of a bad update — and saying so is the whole
 * point of not collapsing these into one generic error.
 *
 * <p>The sentence lives here rather than in the browser so that Fjord says the same thing about an update
 * wherever it is asked: an operator reading the Explorer and an operator reading a log get one answer.
 */
public enum ContainerUpdateOutcome {

    /** The newer image was pulled and the service was recreated on it. The only outcome that is a success. */
    UPDATED,

    /** The pull failed, so nothing was recreated and nothing on the host changed. */
    PULL_FAILED,

    /** The newer image is on the host, but compose could not recreate the service on it. */
    RECREATE_FAILED,

    /** Fjord stopped waiting for a run and abandoned it; what the host did afterwards is unknown. */
    TIMED_OUT,

    /** Fjord could not reach the machine at all — no credential, refused SSH, or a host that never answered. */
    UNREACHABLE;

    /** Whether the container really is running the newer image now. Only one outcome says yes. */
    public boolean updated() {
        return this == UPDATED;
    }

    /**
     * How a finished {@code compose pull} reads: empty when it succeeded and the recreate may follow,
     * otherwise the outcome the whole update ends on. A timeout is reported as a timeout rather than as a
     * failed pull — "we stopped waiting" and "the registry said no" are not the same answer.
     */
    public static Optional<ContainerUpdateOutcome> ofPull(CommandResult result) {
        if (result.timedOut()) {
            return Optional.of(TIMED_OUT);
        }
        return result.exitCode() == 0 ? Optional.empty() : Optional.of(PULL_FAILED);
    }

    /** How a finished {@code compose up -d} reads — the outcome of the update as a whole. */
    public static ContainerUpdateOutcome ofRecreate(CommandResult result) {
        if (result.timedOut()) {
            return TIMED_OUT;
        }
        return result.exitCode() == 0 ? UPDATED : RECREATE_FAILED;
    }

    /**
     * What the operator is told, naming the container. Deliberately free of double quotes so it survives
     * being embedded in the settled event's JSON exactly as written.
     */
    public String sentence(String containerName) {
        return switch (this) {
            case UPDATED -> containerName
                + " was updated to the image its registry now serves, and is running again.";
            case PULL_FAILED -> "Fjord could not pull a newer image for " + containerName
                + ", so nothing was changed — the old container is still running.";
            case RECREATE_FAILED -> "The newer image was pulled, but recreating " + containerName
                + " failed — the old container is still running on the image it had.";
            case TIMED_OUT -> "The update of " + containerName
                + " took longer than Fjord waits and was abandoned. Check the container on its host"
                + " before trying again.";
            case UNREACHABLE -> "Fjord could not reach the host to update " + containerName
                + ", so nothing was changed.";
        };
    }
}
