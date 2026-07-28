package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What to call a machine's backup store on the screen an operator restores from.
 *
 * <p>The store is a directory on the backup server, named after the machine — but since machine names
 * stopped needing to be unique (§6.22), the machine's name alone can name two different stores. Two rows
 * reading "NAS", on the page you go to when you want a file back, is the same "two things wearing one
 * label" failure the identity work removed from the code, arriving on the screen instead.
 */
class BackupStoreLabelTest {

    private static Machine machine(String name, String lanAddress) {
        return new Machine(MachineId.generate(), name, MachineType.LAN_SERVER,
            null, null, null, null, null, null, null, null, lanAddress, true, null,
            DeviceCategory.NAS, null);
    }

    @Test
    void aMachineWhoseNameIsItsOwn_isJustItsName() {
        Machine nas = machine("NAS", "192.168.3.3");
        Machine colina = machine("Colina 27", "192.168.1.118");

        assertThat(BackupStoreLabel.of(nas, List.of(nas, colina))).isEqualTo("NAS");
    }

    @Test
    void twoMachinesSharingAName_areToldApartByWhereTheyAre() {
        // The address, not the repository's directory name: an operator knows which box is at which
        // address, and nobody knows what "NAS-2" means.
        Machine here = machine("NAS", "192.168.3.3");
        Machine there = machine("NAS", "192.168.1.50");

        assertThat(BackupStoreLabel.of(here, List.of(here, there))).isEqualTo("NAS · 192.168.3.3");
        assertThat(BackupStoreLabel.of(there, List.of(here, there))).isEqualTo("NAS · 192.168.1.50");
    }

    @Test
    void aSharedNameWithNoAddressToShow_fallsBackToTheIdentity() {
        // A peer has no LAN address. Something that distinguishes them is still better than two identical
        // rows — ugly on purpose, because the alternative is an operator restoring from the wrong machine.
        Machine here = machine("NAS", null);
        Machine there = machine("NAS", null);

        assertThat(BackupStoreLabel.of(here, List.of(here, there)))
            .isEqualTo("NAS · " + here.id().value());
    }

    @Test
    void theComparisonIsOnTheNameAsShown_notOnCasingOrPadding() {
        // "NAS" and " nas " read as the same label to a person scanning the list, so they are ambiguous
        // even though the strings differ.
        Machine here = machine("NAS", "192.168.3.3");
        Machine there = machine(" nas ", "192.168.1.50");

        assertThat(BackupStoreLabel.of(here, List.of(here, there))).isEqualTo("NAS · 192.168.3.3");
    }

    @Test
    void aMachineNotInTheFleetList_isStillLabelled() {
        Machine gone = machine("NAS", "192.168.3.3");

        assertThat(BackupStoreLabel.of(gone, List.of())).isEqualTo("NAS");
    }
}
