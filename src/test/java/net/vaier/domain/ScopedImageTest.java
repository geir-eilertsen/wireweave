package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedImageTest {

    @Test
    void labelReadsTheImageThenTheMachineItRunsOn() {
        // The whole point of #57's refinement: an operator must be able to tell WHICH machine to act on.
        ScopedImage scoped = new ScopedImage(MachineId.generate().value(), "vaultwarden/server:latest");

        assertThat(scoped.label("Apalveien 5")).isEqualTo("vaultwarden/server:latest on Apalveien 5");
    }

    @Test
    void labelFallsBackToTheIdentityWhenThereIsNoNameToShow() {
        // Ugly on purpose. A machine that has left the fleet between the sweep and the mail has no name to
        // give, and borrowing another machine's would send the operator to the wrong host.
        MachineId gone = MachineId.generate();
        ScopedImage scoped = new ScopedImage(gone.value(), "vaultwarden/server:latest");

        assertThat(scoped.label(null)).isEqualTo("vaultwarden/server:latest on " + gone.value());
        assertThat(scoped.label("  ")).isEqualTo("vaultwarden/server:latest on " + gone.value());
    }

    @Test
    void theSameImageOnTwoMachinesAreDistinctScopedImages() {
        // The tracked unit is image-on-a-machine, not image: two machines running the same tag are two things.
        ScopedImage onA = new ScopedImage(MachineId.generate().value(), "vaultwarden/server:latest");
        ScopedImage onB = new ScopedImage(MachineId.generate().value(), "vaultwarden/server:latest");

        assertThat(onA).isNotEqualTo(onB);
    }

    @Test
    void twoMachinesSharingANameAreStillTwoScopedImages() {
        // The reason the key is an identity. Under name-scoping, two machines both called "NAS" running the
        // same tag collapsed into one verdict — one of them silently stopped being watched.
        ScopedImage here = new ScopedImage(MachineId.generate().value(), "plex:latest");
        ScopedImage there = new ScopedImage(MachineId.generate().value(), "plex:latest");

        assertThat(here.label("NAS")).isEqualTo(there.label("NAS"));
        assertThat(here).isNotEqualTo(there);
    }
}
