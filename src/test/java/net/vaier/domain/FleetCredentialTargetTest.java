package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FleetCredentialTargetTest {

    private static Machine machine(String name, MachineType type, DeviceCategory category,
                                   Boolean sshOverride) {
        return new Machine(TestMachineIds.of(name), name, type, null, null, null, null, null, null, null,
            null, null, false, null, category, sshOverride);
    }

    @Test
    void aServerWithSshAccessAndAStoredLoginRunsAShellVaierCanReach() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS, null);

        assertThat(FleetCredentialTarget.of(nas, true).runsAShellVaierCanReach()).isTrue();
    }

    @Test
    void aPhoneIsNeverOfferedAFleetCredential() {
        Machine phone = machine("phone", MachineType.MOBILE_CLIENT, DeviceCategory.PHONE, null);

        assertThat(FleetCredentialTarget.of(phone, true).runsAShellVaierCanReach()).isFalse();
    }

    @Test
    void aMachineVaierHoldsNoLoginForIsSkipped_neverAnError() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS, null);

        assertThat(FleetCredentialTarget.of(nas, false).runsAShellVaierCanReach()).isFalse();
    }

    @Test
    void anOperatorsSshAccessOverrideIsAuthoritative() {
        Machine off = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS, false);

        assertThat(FleetCredentialTarget.of(off, true).runsAShellVaierCanReach()).isFalse();
    }

    @Test
    void carriesTheMachinesIdentityAndItsDisplayName() {
        Machine nas = machine("nas", MachineType.LAN_SERVER, DeviceCategory.NAS, null);

        FleetCredentialTarget target = FleetCredentialTarget.of(nas, true);

        assertThat(target.machineId()).isEqualTo(TestMachineIds.of("nas"));
        assertThat(target.machineName()).isEqualTo("nas");
    }

    @Test
    void anIneligibleTargetStandsAsSkipped() {
        Machine phone = machine("phone", MachineType.MOBILE_CLIENT, DeviceCategory.PHONE, null);

        assertThat(FleetCredentialTarget.of(phone, true).skippedStanding().state())
            .isEqualTo(FleetCredentialState.SKIPPED);
    }
}
