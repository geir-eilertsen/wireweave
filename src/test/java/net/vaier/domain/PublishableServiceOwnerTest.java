package net.vaier.domain;

import net.vaier.domain.PublishableService.PublishableSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublishableServiceOwnerTest {

    private static PublishableService on(MachineId machineId) {
        return on(machineId, false);
    }

    private static PublishableService on(MachineId machineId, boolean ignored) {
        return new PublishableService(PublishableSource.PEER, machineId == null ? null : machineId.value(),
            "alice", "10.13.13.2", "grafana", 3000, null, ignored);
    }

    @Test
    void aServiceBelongsToTheMachineItWasDiscoveredOn() {
        MachineId alice = MachineId.generate();

        assertThat(on(alice).belongsTo(alice)).isTrue();
    }

    @Test
    void aServiceDoesNotBelongToADifferentMachine() {
        assertThat(on(MachineId.generate()).belongsTo(MachineId.generate())).isFalse();
    }

    @Test
    void aServiceOnAMachineInNoRegistry_belongsToNobody() {
        // A live WireGuard peer with no stored config has no identity to report, so its services cannot be
        // attributed. Attributing them to whatever machine happened to share its name is exactly the
        // mis-attribution the identity carries to prevent.
        assertThat(on(null).belongsTo(MachineId.generate())).isFalse();
    }

    @Test
    void nothingBelongsToANullMachine() {
        assertThat(on(MachineId.generate()).belongsTo(null)).isFalse();
    }

    // --- awaiting publishing: ownership AND an unanswered question ---

    @Test
    void anUnignoredServiceOnTheMachine_awaitsPublishing() {
        MachineId alice = MachineId.generate();

        assertThat(on(alice).awaitsPublishingOn(alice)).isTrue();
    }

    @Test
    void anIgnoredService_awaitsNothing() {
        // Ignoring a service is the operator answering "no, not this one". A nudge is a question, and a
        // question already answered must not be asked again — otherwise the whole nudge rail becomes noise.
        MachineId alice = MachineId.generate();

        assertThat(on(alice, true).awaitsPublishingOn(alice)).isFalse();
    }

    @Test
    void aServiceOnADifferentMachine_awaitsNothingHere() {
        assertThat(on(MachineId.generate()).awaitsPublishingOn(MachineId.generate())).isFalse();
    }
}
