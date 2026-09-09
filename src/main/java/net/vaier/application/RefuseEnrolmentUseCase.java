package net.vaier.application;

import net.vaier.domain.EnrolmentRequest;

import java.util.Optional;

/** The operator turns a waiting phone away (#359 slice 1b). It leaves nothing behind. */
public interface RefuseEnrolmentUseCase {

    /**
     * Removes the request showing this {@code Join code}.
     *
     * @return what was removed, so the refusal can be told to that phone's stream; empty when no
     *         live request showed that code — refusing an unknown code is a no-op, not an error.
     */
    Optional<EnrolmentRequest> refuse(String code);
}
