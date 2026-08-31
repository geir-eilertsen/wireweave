package net.vaier.application;

import net.vaier.domain.FleetCredentialStanding;

import java.util.List;

public interface GetFleetCredentialStandingsUseCase {

    /**
     * Where the fleet credential {@code name} last stood on each machine — empty when Vaier has not
     * looked yet. A read of an observation, never a trip to the fleet: opening a page must not SSH to
     * every machine.
     */
    List<FleetCredentialStanding> getFleetCredentialStandings(String name);
}
