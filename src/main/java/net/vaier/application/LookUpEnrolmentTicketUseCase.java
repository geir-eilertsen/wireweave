package net.vaier.application;

import net.vaier.domain.EnrolmentVerdict;

/** What the holder of an {@code Enrolment ticket} is owed right now (#359 slice 1b). */
public interface LookUpEnrolmentTicketUseCase {

    /**
     * The verdict for this ticket: still waiting, approved with its config, or nothing at all. An
     * unknown ticket and an expired request give the same answer, so a caller learns only that it
     * has nothing coming.
     */
    EnrolmentVerdict lookUp(String ticket);
}
