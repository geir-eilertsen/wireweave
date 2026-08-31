package net.vaier.application;

import net.vaier.domain.FleetCredentialView;

import java.util.List;

public interface GetFleetCredentialsUseCase {

    /**
     * Every stored fleet credential, redacted. Only the domain's own
     * {@link net.vaier.domain.FleetCredential#toView() view} is returned — no content bytes, and no
     * digest of them either.
     */
    List<FleetCredentialView> getFleetCredentials();
}
