package net.vaier.rest;

import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.ImageUpdateRollup;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.ScopedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one place that turns "these images just went stale" into a mail — shared by the daily sweep and the
 * operator's own check, so the two can never word the same alert differently.
 */
class ImageUpdateAlerterTest {

    NotifyAdminsOfUpdateAvailableUseCase notifier;
    GetMachinesUseCase machines;
    ImageUpdateAlerter alerter;

    @BeforeEach
    void setUp() {
        notifier = mock(NotifyAdminsOfUpdateAvailableUseCase.class);
        machines = mock(GetMachinesUseCase.class);
        when(machines.getAllMachines()).thenReturn(List.of());
        alerter = new ImageUpdateAlerter(notifier, machines);
    }

    @Test
    void mailsOneRollupForTheImagesHanded() {
        ScopedImage a = new ScopedImage("m1", "a:1");
        ScopedImage b = new ScopedImage("m1", "b:1");

        alerter.alert(List.of(a, b));

        ArgumentCaptor<ImageUpdateRollup> rollup = ArgumentCaptor.forClass(ImageUpdateRollup.class);
        verify(notifier).notifyAdminsOfUpdateAvailable(rollup.capture());
        assertThat(rollup.getValue().images()).containsExactly(a, b);
    }

    @Test
    void namesTheMachineInTheMail_lookedUpAtTheMomentSomeoneWillReadIt() {
        MachineId colina = MachineId.generate();
        when(machines.getAllMachines()).thenReturn(List.of(new Machine(colina, "Colina 27",
            MachineType.UBUNTU_SERVER, "pk", "10.13.13.3/32", null, null, null, null, null, null, null,
            true, 2375, DeviceCategory.SERVER, null)));

        alerter.alert(List.of(new ScopedImage(colina.value(), "netdata/netdata:stable")));

        ArgumentCaptor<ImageUpdateRollup> rollup = ArgumentCaptor.forClass(ImageUpdateRollup.class);
        verify(notifier).notifyAdminsOfUpdateAvailable(rollup.capture());
        assertThat(rollup.getValue().subject()).contains("Colina 27");
    }

    @Test
    void nothingNewMeansNoMail() {
        alerter.alert(List.of());

        verify(notifier, never()).notifyAdminsOfUpdateAvailable(any());
    }

    @Test
    void aFailedMailIsSwallowed_soNeitherTheScheduleNorTheOperatorsCheckDiesOfIt() {
        doThrow(new RuntimeException("smtp down")).when(notifier).notifyAdminsOfUpdateAvailable(any());

        alerter.alert(List.of(new ScopedImage("m1", "a:1")));   // must not throw
    }
}
