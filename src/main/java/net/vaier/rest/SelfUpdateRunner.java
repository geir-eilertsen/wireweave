package net.vaier.rest;

import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.DiscoverVaierServerContainersUseCase;
import net.vaier.application.GetSelfUpdateStatusUseCase;
import net.vaier.application.RunRemoteCommandUseCase;
import net.vaier.application.UpdateVaierUseCase;
import net.vaier.domain.CommandResult;
import net.vaier.domain.DockerService;
import net.vaier.domain.MachineId;
import net.vaier.domain.SelfUpdate;
import net.vaier.domain.port.ForResolvingRegistryDigest;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
import net.vaier.domain.SelfUpdateScript;
import net.vaier.domain.SelfUpdateStatus;
import org.springframework.stereotype.Component;

/**
 * Carries out Vaier's self-update on its own host.
 *
 * <p>It sits in {@code rest/} beside {@link BackupRunner} and {@link BackupProvisioner} for the same reason
 * they do: it drives a detached process over SSH and reads its result back later, which is infrastructure
 * work, not a domain decision. Every decision it makes is asked of the domain — which container is Vaier
 * ({@link SelfUpdate#findSelf}), whether there is anything to do ({@link SelfUpdate#updateAvailable}),
 * what the host should run ({@link SelfUpdateScript}) and what came back ({@link SelfUpdateStatus#parse}).
 *
 * <p><b>SSH to its own host.</b> The same channel {@code RemoteDiskWatcher} uses to read the Vaier server's
 * disks and {@code BackupRunner} uses to back it up — proven daily, with root, by the nightly job. That is
 * what makes this possible at all: the update has to outlive the container, so it cannot run inside it.
 */
@Component
@Slf4j
public class SelfUpdateRunner implements UpdateVaierUseCase, GetSelfUpdateStatusUseCase {

    private final DiscoverVaierServerContainersUseCase containers;
    private final RunRemoteCommandUseCase remoteCommand;
    private final ForResolvingVaierServerIdentity vaierServerIdentity;
    // Settings speaks for itself (#353): Vaier's own stack is no longer swept, so the one image this may
    // act on is asked about here rather than read off a verdict nobody takes any more. Cached answers are
    // fine and deliberate — this is one image, and the rate limit is shared with the whole fleet.
    private final ForResolvingRegistryDigest registryDigest;

    public SelfUpdateRunner(DiscoverVaierServerContainersUseCase containers,
                            RunRemoteCommandUseCase remoteCommand,
                            ForResolvingVaierServerIdentity vaierServerIdentity,
                            ForResolvingRegistryDigest registryDigest) {
        this.containers = containers;
        this.remoteCommand = remoteCommand;
        this.vaierServerIdentity = vaierServerIdentity;
        this.registryDigest = registryDigest;
    }

    /**
     * The Vaier server's own identity. It is the one machine that cannot be found by searching the machine
     * stores — it is neither a peer nor a LAN server — so it is asked for through a driven port rather than
     * borrowed from another domain's use case. Resolved per call rather than held: this component outlives a
     * config reload, and an id cached here would go on addressing a machine the config no longer describes.
     */
    private MachineId vaierServerId() {
        return vaierServerIdentity.identity();
    }

    @Override
    public boolean updateAvailable() {
        try {
            return SelfUpdate.updateAvailable(containers.discover(), registryDigest);
        } catch (Exception e) {
            log.debug("Could not judge whether a Vaier update is available: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SelfUpdateStatus lastUpdate() {
        try {
            CommandResult result = remoteCommand.run(vaierServerId(), SelfUpdateScript.readResult());
            return SelfUpdateStatus.parse(result.stdout());
        } catch (Exception e) {
            log.debug("Could not read the last self-update result: {}", e.getMessage());
            return SelfUpdateStatus.NONE;
        }
    }

    /**
     * Stage the update script on the host and launch it detached. Every refusal below is a refusal to take
     * Vaier down: without its own container it does not know what to recreate, and without compose labels
     * there is no {@code docker compose up} that would bring it back — an update there would stop Vaier and
     * leave it stopped.
     */
    @Override
    public SelfUpdateStatus updateSelf() {
        String runId = "update-" + System.currentTimeMillis();
        List<DockerService> found;
        try {
            found = containers.discover();
        } catch (Exception e) {
            return failed(runId, "cannot-read-own-containers");
        }
        Optional<DockerService> self = SelfUpdate.findSelf(found);
        if (self.isEmpty()) {
            return failed(runId, "vaier-container-not-found");
        }

        Optional<SelfUpdateScript.ComposeLocation> at;
        try {
            CommandResult labels = remoteCommand.run(vaierServerId(),
                SelfUpdateScript.inspectComposeLabels(self.get().containerId()));
            at = SelfUpdateScript.parseComposeLabels(labels.stdout());
        } catch (Exception e) {
            log.warn("Could not reach the Vaier host to update: {}", e.getMessage());
            return failed(runId, "host-unreachable");
        }
        if (at.isEmpty()) {
            return failed(runId, "not-started-by-compose");
        }

        String script = SelfUpdateScript.generate(at.get().workingDir(), at.get().service(), runId,
            SelfUpdateScript.DEFAULT_HEALTH_TIMEOUT_SECONDS);
        String path = SelfUpdateScript.scriptPathFor(at.get().workingDir(), runId);
        try {
            CommandResult staged = remoteCommand.run(vaierServerId(),
                SelfUpdateScript.stage(script, path));
            if (staged.exitCode() != 0) {
                return failed(runId, "could-not-stage-script");
            }
            // From here the host owns it. This process is about to be replaced, so nothing after this line
            // can be relied on to run — which is why the script, not Vaier, decides how the update ends.
            remoteCommand.run(vaierServerId(), SelfUpdateScript.launch(at.get().workingDir(), runId));
            log.info("Vaier self-update {} launched on its own host", runId);
            return new SelfUpdateStatus(runId, SelfUpdateStatus.Outcome.NONE, null, "started");
        } catch (Exception e) {
            log.warn("Launching the Vaier self-update failed: {}", e.getMessage());
            return failed(runId, "launch-failed");
        }
    }

    private SelfUpdateStatus failed(String runId, String why) {
        log.warn("Vaier self-update {} refused: {}", runId, why);
        return new SelfUpdateStatus(runId, SelfUpdateStatus.Outcome.FAILED, null, why);
    }
}
