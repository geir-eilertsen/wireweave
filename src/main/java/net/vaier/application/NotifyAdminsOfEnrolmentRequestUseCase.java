package net.vaier.application;

import net.vaier.domain.EnrolmentRequest;

/**
 * Tell admins a phone is waiting on a join code. The request lives ten minutes and needs a person;
 * a mail is what reaches one who does not have the fleet page open.
 */
public interface NotifyAdminsOfEnrolmentRequestUseCase {
    void notifyAdminsOfEnrolmentRequest(EnrolmentRequest request);
}
