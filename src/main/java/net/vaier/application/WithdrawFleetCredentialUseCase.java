package net.vaier.application;

import net.vaier.domain.FleetCredentialStanding;
import net.vaier.domain.NotFoundException;

import java.util.List;

public interface WithdrawFleetCredentialUseCase {

    /**
     * Remove the fleet credential {@code name} from every machine it could have been delivered to, and
     * report where it stands on each. This is the one place a secret on N hosts is revoked, which is why
     * it ships alongside distribution rather than after it.
     *
     * <p>It also stands the credential down, so the background reconcile stops healing it back.
     *
     * @throws NotFoundException no fleet credential is stored under that name
     */
    List<FleetCredentialStanding> withdrawFleetCredential(String name);
}
