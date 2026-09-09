package net.vaier.application;

import net.vaier.domain.EnrolmentRequest;

import java.util.List;

/** The phones currently waiting to be approved into the fleet (#359 slice 1b). */
public interface ListEnrolmentRequestsUseCase {

    /** Still waiting and not yet expired. Never an already-approved request. */
    List<EnrolmentRequest> pending();
}
