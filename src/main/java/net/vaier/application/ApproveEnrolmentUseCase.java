package net.vaier.application;

import net.vaier.application.EnrolDeviceUseCase.EnrolledDeviceUco;
import net.vaier.domain.NotFoundException;

/**
 * The operator admits a waiting phone (#359 slice 1b) from wherever they are already signed in.
 *
 * <p>Approval runs exactly the enrolment {@link EnrolDeviceUseCase} runs — same peer, same key-free
 * config — and then records the config against the request so it can reach the phone's own event
 * stream.
 */
public interface ApproveEnrolmentUseCase {

    /**
     * Approves the request showing this {@code Join code}.
     *
     * @throws NotFoundException if no live request shows that code
     */
    ApprovedEnrolmentUco approve(String code);

    /**
     * @param ticket the approved request's {@code Enrolment ticket} — how the caller knows which
     *               stream the config belongs on. It never leaves the server on any other route.
     */
    record ApprovedEnrolmentUco(String ticket, EnrolledDeviceUco device) {}
}
