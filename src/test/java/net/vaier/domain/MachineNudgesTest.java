package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure-domain assembler that composes a machine's applicable nudges from the individual
 * {@link MachineNudge} factories. The "should X fire?" decisions live in those factories; this only
 * collects the ones that apply — so these tests pin the composition (which fire, in what order),
 * not the per-nudge predicates (those are {@link MachineNudgeTest}).
 */
class MachineNudgesTest {

    private static Machine machine(DeviceCategory category) {
        return new Machine(MachineId.generate(), "nas", MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            null, null, null, null, null, true, null, category, null);
    }

    private static BackupJob job(boolean backupAsRoot) {
        return new BackupJob("nas", TestMachineIds.of("nas"), "nas-repo", List.of("/home"), List.of(),
            7, 4, 6, "zstd,6", true, backupAsRoot);
    }

    private static BackupRun incompleteRun(BackupJob theJob) {
        return BackupRun.fromExitCode(theJob, "run-1", Instant.EPOCH, Instant.EPOCH, 1,
            "/home/mqtt/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'\n");
    }

    @Test
    void composesAllThreeWhenEveryConditionHolds() {
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.SERVER), 2, true, true, Optional.empty(), Optional.empty(),
            new BackupFleet(List.of()));

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.PUBLISH, MachineNudge.Kind.BACK_UP, MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
        assertThat(nudges).allSatisfy(n -> assertThat(n.machineName()).isEqualTo("nas"));
    }

    @Test
    void composesNothingWhenNoConditionHolds() {
        BackupServer existing = new BackupServer("nas-borg", TestMachineIds.of("nas"), "192.168.3.50",
            8022, "borg", null, "/volume1/docker/borg", true);
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.PRINTER), 0, false, false, Optional.of(job(true)),
            Optional.empty(), new BackupFleet(List.of(existing)));

        assertThat(nudges).isEmpty();
    }

    @Test
    void composesOnlyTheNudgesThatApply() {
        // storage-class + no backup server ⇒ DESIGNATE; but unreachable/no-cred ⇒ no BACK_UP;
        // and nothing publishable ⇒ no PUBLISH.
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.NAS), 0, false, false, Optional.empty(), Optional.empty(),
            new BackupFleet(List.of()));

        assertThat(nudges).extracting(MachineNudge::kind)
            .containsExactly(MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);
    }

    @Test
    void aMachineWithAJobIsAlreadyProtected_andAnIncompleteRunAddsTheRootNudge() {
        // "Already protected" is not a boolean the caller works out and passes in — it is "this machine has
        // a job", which the assembler reads off the job it needs anyway for the back-up-as-root decision.
        BackupJob theJob = job(false);
        List<MachineNudge> nudges = MachineNudges.forMachine(
            machine(DeviceCategory.SERVER), 0, true, true, Optional.of(theJob),
            Optional.of(incompleteRun(theJob)), new BackupFleet(List.of()));

        assertThat(nudges).extracting(MachineNudge::kind).containsExactly(
            MachineNudge.Kind.DESIGNATE_BACKUP_SERVER, MachineNudge.Kind.BACK_UP_AS_ROOT);
    }
}
