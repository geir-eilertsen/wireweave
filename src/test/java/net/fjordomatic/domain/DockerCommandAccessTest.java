package net.fjordomatic.domain;

import net.fjordomatic.domain.port.ForRecordingDockerCommandAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Whether Fjord's SSH user can drive Docker on a machine — learned on the disk sweep's own trip, because
 * the container scrape goes over the Docker API and can never answer it.
 */
class DockerCommandAccessTest {

    private static final MachineId COLINA = TestMachineIds.of("colina27");

    private final ForRecordingDockerCommandAccess recorder = mock(ForRecordingDockerCommandAccess.class);

    private static CommandResult swept(String stdout) {
        return new CommandResult(0, stdout, "", false, "SHA256:x");
    }

    // --- the probe rides in front of the sweep's own command ---

    @Test
    void theProbeRunsAheadOfTheCommandItRidesWith_soThatCommandStillGovernsTheResult() {
        String combined = DockerCommandAccess.probeAheadOf(RemoteDiskUsage.DF_COMMAND);

        assertThat(combined).endsWith(RemoteDiskUsage.DF_COMMAND);
        assertThat(combined.indexOf("docker")).isLessThan(combined.indexOf(RemoteDiskUsage.DF_COMMAND));
    }

    @Test
    void theProbeSaysNothingOnTheStreamsTheSweepReads() {
        // Its output and its errors are discarded; only the marker line speaks. A probe that printed a
        // permission error into df's own stderr would be a second thing to explain on every sweep.
        assertThat(DockerCommandAccess.probeAheadOf(RemoteDiskUsage.DF_COMMAND))
            .contains(">/dev/null 2>&1");
    }

    @Test
    void theProbesMarkerIsNotADfRow_soTheDiskReadingIsUnaffected() {
        // The two facts share one connection and therefore one stdout. This is the whole safety of that:
        // the marker cannot be mistaken for a filesystem, so the disk reading reads exactly as before.
        String sweptOutput = "VAIER-DOCKER-RC=0\n"
            + "Filesystem 1024-blocks Used Available Capacity Mounted on\n"
            + "/dev/sda1 100000 50000 50000 50% /";

        List<RemoteDiskUsage> filesystems = RemoteDiskUsage.parseList("colina27", sweptOutput);

        assertThat(filesystems).singleElement()
            .satisfies(fs -> assertThat(fs.mountPoint()).isEqualTo("/"));
    }

    // --- reading the answer ---

    @Test
    void aProbeThatExitedCleanly_meansFjordCanRunDockerThere() {
        assertThat(DockerCommandAccess.readFrom(swept("VAIER-DOCKER-RC=0\nFilesystem 1024-blocks Used Available Capacity Mounted on")))
            .isEqualTo(DockerCommandAccess.GRANTED);
    }

    @Test
    void aProbeThatFailed_meansFjordCannotRunDockerThere() {
        // Exit 1 is what a docker.sock permission denial produces — but any non-zero exit means the same
        // thing for the question being asked: Fjord cannot drive Docker as this user on this machine.
        assertThat(DockerCommandAccess.readFrom(swept("VAIER-DOCKER-RC=1\n/dev/sda1 1 1 1 1% /")))
            .isEqualTo(DockerCommandAccess.REFUSED);
    }

    @Test
    void outputWithNoMarkerAtAll_isUnknown_neverANo() {
        assertThat(DockerCommandAccess.readFrom(swept("Filesystem 1024-blocks Used Available Capacity Mounted on")))
            .isEqualTo(DockerCommandAccess.UNKNOWN);
        assertThat(DockerCommandAccess.readFrom(swept(""))).isEqualTo(DockerCommandAccess.UNKNOWN);
        assertThat(DockerCommandAccess.readFrom(null)).isEqualTo(DockerCommandAccess.UNKNOWN);
    }

    @Test
    void onlyARefusalIsARefusal() {
        assertThat(DockerCommandAccess.REFUSED.refused()).isTrue();
        assertThat(DockerCommandAccess.GRANTED.refused()).isFalse();
        assertThat(DockerCommandAccess.UNKNOWN.refused()).isFalse();
    }

    // --- retaining what the sweep saw ---

    @Test
    void aSweepThatLearnedSomething_recordsIt() {
        DockerCommandAccess.retain(COLINA, swept("VAIER-DOCKER-RC=1\n"), recorder);

        verify(recorder).record(COLINA, DockerCommandAccess.REFUSED);
    }

    @Test
    void aSweepThatLearnedNothing_recordsNothing_soAKnownFactIsNeverErasedByASilentTrip() {
        // A machine that was asleep, or a trip whose output never carried the marker, says nothing new
        // about Docker. Writing UNKNOWN would forget a fact Fjord holds and re-offer a doomed button.
        DockerCommandAccess.retain(COLINA, swept("Filesystem 1024-blocks Used Available Capacity Mounted on"), recorder);

        verifyNoInteractions(recorder);
    }

    @Test
    void aSweepIsStillTakenOnAMachineAlreadyKnownToRefuse_soItSelfHealsWhenTheGroupIsFixed() {
        // Nothing here consults the previous value: the probe rides on every sweep, and the moment the
        // operator adds Fjord's user to the docker group the next sweep records GRANTED.
        DockerCommandAccess.retain(COLINA, swept("VAIER-DOCKER-RC=0\n"), recorder);

        verify(recorder).record(COLINA, DockerCommandAccess.GRANTED);
    }
}
