package net.fjordomatic.application;

public interface RevokeAccessUseCase {

    /** Remove the access entry for {@code email} entirely. */
    void revokeAccess(String email);
}
