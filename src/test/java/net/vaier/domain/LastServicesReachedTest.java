package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LastServicesReachedTest {

    private static final Instant NOON = Instant.parse("2026-08-11T12:00:00Z");
    private static final MachineId PHONE = TestMachineIds.of("phone");
    private static final MachineId LAPTOP = TestMachineIds.of("laptop");

    @Test
    void aMachineThatHasReachedNothingKnowsOfNoService() {
        assertThat(LastServicesReached.empty().forMachine(PHONE)).isEmpty();
        assertThat(LastServicesReached.empty().forMachine(null)).isEmpty();
    }

    @Test
    void remembersTheServiceAMachineReached() {
        LastServicesReached reached = LastServicesReached.empty()
            .with(new LastServiceReached(PHONE, "grafana.example.com", NOON));

        assertThat(reached.forMachine(PHONE))
            .contains(new LastServiceReached(PHONE, "grafana.example.com", NOON));
    }

    @Test
    void theMostRecentServiceWins() {
        LastServicesReached reached = LastServicesReached.empty()
            .with(new LastServiceReached(PHONE, "grafana.example.com", NOON))
            .with(new LastServiceReached(PHONE, "plex.example.com", NOON.plusSeconds(60)));

        assertThat(reached.forMachine(PHONE)).map(LastServiceReached::host).contains("plex.example.com");
        assertThat(reached.entries()).hasSize(1);
    }

    /** Requests arrive concurrently and clocks between hops differ; a late straggler is not the latest. */
    @Test
    void anOutOfOrderReachDoesNotOverwriteANewerOne() {
        LastServicesReached reached = LastServicesReached.empty()
            .with(new LastServiceReached(PHONE, "plex.example.com", NOON))
            .with(new LastServiceReached(PHONE, "grafana.example.com", NOON.minusSeconds(30)));

        assertThat(reached.forMachine(PHONE)).map(LastServiceReached::host).contains("plex.example.com");
    }

    @Test
    void oneMachinesReachLeavesEveryOtherMachineAlone() {
        LastServicesReached reached = LastServicesReached.empty()
            .with(new LastServiceReached(PHONE, "grafana.example.com", NOON))
            .with(new LastServiceReached(LAPTOP, "plex.example.com", NOON.plusSeconds(5)));

        assertThat(reached.forMachine(PHONE)).map(LastServiceReached::host).contains("grafana.example.com");
        assertThat(reached.forMachine(LAPTOP)).map(LastServiceReached::host).contains("plex.example.com");
    }

    @Test
    void isBuiltFromWhateverTheStoreHeld() {
        LastServicesReached reached = LastServicesReached.of(List.of(
            new LastServiceReached(PHONE, "grafana.example.com", NOON)));

        assertThat(reached.entries()).hasSize(1);
        assertThat(LastServicesReached.of(null).entries()).isEmpty();
    }
}
