package net.vaier.application;

import net.vaier.domain.MachineDiskStanding;

import java.util.List;

/**
 * Read the whole fleet's <b>machine disk standing</b>s in one go — what the Explorer's fleet listing marks
 * each machine card with.
 *
 * <p><b>It wakes nothing.</b> The standings are what the 5-minute {@code df} sweep already found, held in
 * memory; this is a read of that, not a fleet-wide {@code df} on page load — which would wake every sleeping
 * machine to answer a question nobody asked. A machine the sweep has not reached (a cold start, no SSH, no
 * credential) simply has no standing, and a card must then draw no mark at all: absence is not health.
 */
public interface GetMachineDiskStandingsUseCase {

    /** Every standing Vaier currently holds — never one per machine, only per machine it has read. */
    List<MachineDiskStanding> getMachineDiskStandings();
}
