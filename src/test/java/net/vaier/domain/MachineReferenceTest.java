package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which machine the model meant (#360). Names are how the fleet read names machines and how the operator
 * talks, but a name is not an identity — two machines may share one — so the id is accepted too and an
 * ambiguous name is refused with the ids to choose from.
 */
class MachineReferenceTest {

    private static final MachineId COLINA_ID = MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958");
    private static final MachineId NUC_ID = MachineId.of("7b0d1f9a-2c4e-4c1e-9d3a-1f2e3d4c5b6a");
    private static final MachineId TWIN_ID = MachineId.of("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    private static Machine machine(MachineId id, String name) {
        return new Machine(id, name, MachineType.UBUNTU_SERVER, "PUBLICKEY", "10.13.13.3/32",
            null, null, null, null, null, null, null, false, null, DeviceCategory.SERVER, null);
    }

    private final List<Machine> fleet = List.of(
        machine(COLINA_ID, "Colina 27"), machine(NUC_ID, "nuc02"), machine(TWIN_ID, "nuc02"));

    @Test
    void aMachineIsFoundByItsNameAsTheFleetReadGivesIt() {
        assertThat(new MachineReference("Colina 27").resolve(fleet).id()).isEqualTo(COLINA_ID);
    }

    /** The operator says "colina 27" or "colina27"; neither is a different machine. */
    @Test
    void caseSpacingAndPunctuationDoNotMakeADifferentName() {
        assertThat(new MachineReference("colina 27").resolve(fleet).id()).isEqualTo(COLINA_ID);
        assertThat(new MachineReference("colina27").resolve(fleet).id()).isEqualTo(COLINA_ID);
        assertThat(new MachineReference("  COLINA-27 ").resolve(fleet).id()).isEqualTo(COLINA_ID);
    }

    @Test
    void aMachineIsFoundByItsIdToo() {
        assertThat(new MachineReference(TWIN_ID.value()).resolve(fleet).id()).isEqualTo(TWIN_ID);
    }

    @Test
    void anUnknownNameIsRefusedInWords() {
        assertThatThrownBy(() -> new MachineReference("Apalveien").resolve(fleet))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no machine called \"Apalveien\"");
    }

    @Test
    void aNameTwoMachinesShareIsRefusedWithTheIdsToChooseFrom() {
        assertThatThrownBy(() -> new MachineReference("nuc02").resolve(fleet))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("2 machines are called \"nuc02\"")
            .hasMessageContaining(NUC_ID.value())
            .hasMessageContaining(TWIN_ID.value());
    }

    @Test
    void noMachineNamedAtAllIsSaidAsSuch() {
        assertThatThrownBy(() -> new MachineReference("  ").resolve(fleet))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which machine.");
        assertThatThrownBy(() -> new MachineReference(null).resolve(fleet))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Say which machine.");
    }
}
