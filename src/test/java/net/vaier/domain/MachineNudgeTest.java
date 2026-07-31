package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MachineNudgeTest {

    private static Machine storageMachine(String name, DeviceCategory category) {
        return new Machine(MachineId.generate(), name, MachineType.UBUNTU_SERVER, "pk", "10.13.13.9/32", null, null,
            null, null, null, null, null, true, null, category, null);
    }

    // --- shape / invariants ---

    @Test
    void rejectsBlankFields() {
        assertThatThrownBy(() -> new MachineNudge("", MachineNudge.Kind.PUBLISH, "t", "e", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", null, "t", "e", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", MachineNudge.Kind.PUBLISH, " ", "e", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", MachineNudge.Kind.PUBLISH, "t", "", "a"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MachineNudge("nas", MachineNudge.Kind.PUBLISH, "t", "e", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void carriesMachineKindTitleEvidenceAction() {
        MachineNudge nudge = new MachineNudge("nas", MachineNudge.Kind.BACK_UP, "Back up nas", "why", "do it");
        assertThat(nudge.machineName()).isEqualTo("nas");
        assertThat(nudge.kind()).isEqualTo(MachineNudge.Kind.BACK_UP);
        assertThat(nudge.title()).isEqualTo("Back up nas");
        assertThat(nudge.evidence()).isEqualTo("why");
        assertThat(nudge.action()).isEqualTo("do it");
    }

    // --- PUBLISH predicate ---

    @Test
    void publish_firesWhenServicesExposed() {
        Optional<MachineNudge> nudge = MachineNudge.publish("alice", 3);
        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.PUBLISH);
        assertThat(nudge.get().evidence()).contains("3 services");
    }

    @Test
    void publish_emptyWhenNothingExposed() {
        assertThat(MachineNudge.publish("alice", 0)).isEmpty();
        assertThat(MachineNudge.publish("alice", -1)).isEmpty();
    }

    // --- BACK_UP predicate ---

    @Test
    void backUp_firesWhenReachableCredentialedAndUnprotected() {
        assertThat(MachineNudge.backUp("nas", true, true, false)).isPresent();
    }

    @Test
    void backUp_emptyWhenMissingAnySignal() {
        assertThat(MachineNudge.backUp("nas", false, true, false)).isEmpty();   // unreachable
        assertThat(MachineNudge.backUp("nas", true, false, false)).isEmpty();   // no credential
        assertThat(MachineNudge.backUp("nas", true, true, true)).isEmpty();     // already protected
    }

    // --- DESIGNATE_BACKUP_SERVER predicate (both directions) ---

    @Test
    void designate_firesForStorageClassMachineWhenNoServerExists() {
        Optional<MachineNudge> nudge = MachineNudge.designateBackupServer(
            storageMachine("nas", DeviceCategory.NAS), new BackupFleet(List.of()));
        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.DESIGNATE_BACKUP_SERVER);

        assertThat(MachineNudge.designateBackupServer(
            storageMachine("box", DeviceCategory.SERVER), new BackupFleet(List.of()))).isPresent();
    }

    @Test
    void designate_emptyWhenABackupServerAlreadyExists() {
        BackupServer existing = new BackupServer("nas-borg", TestMachineIds.of("nas"), "192.168.3.50",
            8022, "borg", null, "/volume1/docker/borg", true);
        assertThat(MachineNudge.designateBackupServer(
            storageMachine("nas", DeviceCategory.NAS), new BackupFleet(List.of(existing)))).isEmpty();
    }

    @Test
    void designate_emptyForNonStorageClassMachine() {
        assertThat(MachineNudge.designateBackupServer(
            storageMachine("printer", DeviceCategory.PRINTER), new BackupFleet(List.of()))).isEmpty();
        assertThat(MachineNudge.designateBackupServer(
            storageMachine("phone", DeviceCategory.PHONE), new BackupFleet(List.of()))).isEmpty();
    }

    // --- BACK_UP_AS_ROOT predicate (#334) ---
    //
    // The checkbox used to ask this in advance, of an operator with no evidence. The nudge asks it once
    // there IS evidence: a run that settled incomplete because borg was denied on files the job protects.

    private static BackupJob job(boolean backupAsRoot) {
        return new BackupJob("colina", TestMachineIds.of("colina"), "colina-repo", List.of("/home"),
            List.of(), 7, 4, 6, "zstd,6", true, backupAsRoot);
    }

    /** A settled run of {@code job} carrying {@code output} as borg's captured tail. */
    private static BackupRun runWith(BackupJob theJob, int exitCode, String output) {
        return BackupRun.fromExitCode(theJob, "run-1", Instant.EPOCH, Instant.EPOCH, exitCode, output);
    }

    private static final String DENIALS = """
        /home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'mosquitto.db'
        /home/pihole/gravity.db: open: [Errno 13] Permission denied: 'gravity.db'
        """;

    @Test
    void backUpAsRoot_firesOnAnIncompleteRunThatLostFilesToPermissions() {
        BackupJob theJob = job(false);
        Optional<MachineNudge> nudge = MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 1, DENIALS)), Optional.of(theJob));

        assertThat(nudge).isPresent();
        assertThat(nudge.get().kind()).isEqualTo(MachineNudge.Kind.BACK_UP_AS_ROOT);
        assertThat(nudge.get().machineName()).isEqualTo("colina");
        // The count is the fact that decides whether the operator acts, and the files are the evidence.
        assertThat(nudge.get().title()).contains("2 files");
        assertThat(nudge.get().evidence()).contains("/home/mqtt/data/mosquitto.db", "/home/pihole/gravity.db");
        assertThat(nudge.get().action()).isNotBlank();
    }

    @Test
    void backUpAsRoot_saysItInTheSingularForOneLostFile() {
        BackupJob theJob = job(false);
        Optional<MachineNudge> nudge = MachineNudge.backUpAsRoot("colina", Optional.of(runWith(theJob, 1,
            "/home/mqtt/data/mosquitto.db: open: [Errno 13] Permission denied: 'x'\n")), Optional.of(theJob));

        assertThat(nudge).isPresent();
        assertThat(nudge.get().title()).contains("1 file").doesNotContain("1 files");
    }

    @Test
    void backUpAsRoot_emptyWhenTheJobAlreadyBacksUpAsRoot() {
        // Root already reads everything; whatever it could not read, this nudge cannot fix.
        BackupJob asRoot = job(true);
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(asRoot, 1, DENIALS)), Optional.of(asRoot))).isEmpty();
    }

    @Test
    void backUpAsRoot_emptyWhenTheRunFailedForAnyOtherReason() {
        BackupJob theJob = job(false);
        // A plain warning (a file changed mid-read) — nothing was denied, nothing is missing.
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 1, "file changed while we backed it up")), Optional.of(theJob)))
            .isEmpty();
        // borg gave up entirely: there is no archive at all, which is a different problem.
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 2, "Connection refused")), Optional.of(theJob))).isEmpty();
        // A clean run says nothing, even if its tail happens to mention permissions.
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 0, "2 files skipped (permission denied)")), Optional.of(theJob)))
            .isEmpty();
    }

    @Test
    void backUpAsRoot_emptyWithoutAJobOrARun() {
        BackupJob theJob = job(false);
        assertThat(MachineNudge.backUpAsRoot("colina", Optional.empty(), Optional.of(theJob))).isEmpty();
        assertThat(MachineNudge.backUpAsRoot("colina",
            Optional.of(runWith(theJob, 1, DENIALS)), Optional.empty())).isEmpty();
    }
}
