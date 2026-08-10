package net.vaier.rest;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.GetVaierServerUseCase;
import net.vaier.application.WriteSurvivalKitUseCase;
import net.vaier.domain.BackupServer;
import net.vaier.domain.Machine;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.MachineId;
import net.vaier.domain.RecoverySheet;
import net.vaier.domain.SurvivalKit;
import net.vaier.domain.SurvivalKitFreshness;
import net.vaier.domain.SurvivalKitHosts;
import net.vaier.domain.SurvivalKitRollout;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForEncryptingSurvivalKits;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForKeepingSurvivalKits;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForReadingTheConfigKey;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Assembles a {@link SurvivalKit} and rolls it out — the orchestration behind one operator decision.
 *
 * <p>It sits here rather than in a {@code *Service} for the same reason {@code BackupRunner} and
 * {@code BackupProvisioner} do: the work spans domains it does not own. It needs the fleet's machines, the
 * backup stores, geolocation, the config store and an SSH path to every host, and pulling those together is
 * driving-edge composition. Every decision it looks like it is making belongs to something else —
 * {@link SurvivalKitHosts} chooses the hosts, {@link SurvivalKit} renders and locks the file,
 * {@link SurvivalKitRollout} decides what counts as having succeeded. This class only carries values
 * between them.
 */
@Component
@Slf4j
public class SurvivalKitWriter implements WriteSurvivalKitUseCase {

    private final GetMachinesUseCase machines;
    private final GetVaierServerUseCase vaierServer;
    private final ForPersistingBackupServers servers;
    private final ForPersistingBackupRepositories repositories;
    private final ForPersistingBackupJobs jobs;
    private final ForGeolocatingIps geo;
    private final ForEncryptingSurvivalKits cipher;
    private final ForKeepingSurvivalKits keeper;
    private final ForPersistingAppConfiguration configuration;
    private final ForReadingTheConfigKey configKey;
    private final Clock clock;

    public SurvivalKitWriter(GetMachinesUseCase machines,
                             GetVaierServerUseCase vaierServer,
                             ForPersistingBackupServers servers,
                             ForPersistingBackupRepositories repositories,
                             ForPersistingBackupJobs jobs,
                             ForGeolocatingIps geo,
                             ForEncryptingSurvivalKits cipher,
                             ForKeepingSurvivalKits keeper,
                             ForPersistingAppConfiguration configuration,
                             ForReadingTheConfigKey configKey,
                             Clock clock) {
        this.machines = machines;
        this.vaierServer = vaierServer;
        this.servers = servers;
        this.repositories = repositories;
        this.jobs = jobs;
        this.geo = geo;
        this.cipher = cipher;
        this.keeper = keeper;
        this.configuration = configuration;
        this.configKey = configKey;
        this.clock = clock;
    }

    /**
     * How often Vaier checks that what the fleet is holding still says what Vaier knows. Ten minutes: the
     * sweep costs a render and a hash when nothing changed, and the thing it guards against — a kit going
     * quietly out of date — is measured in weeks, not seconds. A burst of related changes (a repository and
     * its job arrive together) coalesces into one rollout rather than three.
     */
    private static final long SWEEP_INTERVAL_MS = 10 * 60 * 1000L;

    @Override
    public SurvivalKitReport writeSurvivalKit() {
        VaierConfig config = configuration.load().orElseGet(() -> VaierConfig.builder().build());
        if (!config.hasSurvivalKitPassphrase()) {
            // Checked before anything is rendered or written, so a fleet is never left holding a kit nobody
            // chose the passphrase for. Vaier will not invent one: a kit Vaier could open dies with Vaier.
            throw new IllegalStateException(
                "Choose a survival kit passphrase before writing a kit — Vaier will not invent one, "
                    + "because a kit it could open by itself would die with it");
        }
        return write(config, read());
    }

    /**
     * Keep the fleet's kits saying what Vaier knows, without being asked.
     *
     * <p>This is the whole point of the feature, not a convenience on top of it. A printed sheet was rejected
     * because it goes stale the moment a passphrase changes and you go on believing you are covered; a kit
     * rewritten only when someone presses a button has exactly that flaw with a nicer surface.
     *
     * <p>It never throws. It runs on a timer, and a scheduled method that dies takes its schedule with it —
     * the failure mode would be Vaier silently stopping watching, which is the one this exists to prevent.
     */
    @Scheduled(fixedDelay = SWEEP_INTERVAL_MS, initialDelay = 90_000L)
    public void keepTheFleetsKitsCurrent() {
        try {
            VaierConfig config = configuration.load().orElseGet(() -> VaierConfig.builder().build());
            if (!config.hasSurvivalKitPassphrase()) {
                // Not a failure and not a nag: the operator's one decision has not been made yet, and a
                // sweep is not a way around it.
                return;
            }
            KitInputs inputs = read();
            if (inputs.repositories().isEmpty()) {
                // Nothing is being backed up, so there is nothing to survive. Putting a file on every host of
                // a fleet to tell it so is noise.
                return;
            }
            String current = SurvivalKitFreshness.fingerprintOf(inputs.sheet());
            if (!SurvivalKitFreshness.staleAgainst(config.getSurvivalKitFingerprint(), current)) {
                return;
            }
            log.info("The fleet's survival kits no longer say what Vaier knows — writing them again");
            write(config, inputs);
        } catch (RuntimeException e) {
            log.warn("Could not check whether the fleet's survival kits are current", e);
        }
    }

    /** Render, lock, distribute — and record what was written, but only if all of it landed. */
    private SurvivalKitReport write(VaierConfig config, KitInputs inputs) {
        SurvivalKitHosts.Selection selection =
            SurvivalKitHosts.select(inputs.fleet(), vaierServer.getVaierServerMachine().name(), geo);

        String kit = SurvivalKit.render(inputs.server(), inputs.repositories(), inputs.jobs(), inputs.names(),
            inputs.configKey(), config.getSurvivalKitPassphrase(), clock.instant(), cipher);

        SurvivalKitRollout.Result rollout = SurvivalKitRollout.distribute(selection, kit, keeper);
        if (rollout.reachedEveryDestination()) {
            remember(SurvivalKitFreshness.fingerprintOf(inputs.sheet()));
            log.info("Survival kit written to {} fleet machine(s)", rollout.copiesKept());
        } else if (rollout.survivesLossOfVaier()) {
            // Deliberately not recorded: a destination that refused holds nothing, or holds something older,
            // and no later change to the fleet would reveal that. Left unwritten, the next sweep tries again.
            log.warn("Survival kit written to {} fleet machine(s); {} would not take it, so it will be "
                + "tried again", rollout.copiesKept(), rollout.failures().size());
        } else {
            log.warn("No fleet machine took the survival kit — nothing written now outlives this server");
        }
        return new SurvivalKitReport(selection, rollout);
    }

    /**
     * Everything a kit is made of, read once so a sweep and the write it triggers cannot disagree about what
     * the fleet looked like — and so the fingerprint compared is the fingerprint of what was actually sent.
     */
    private record KitInputs(List<Machine> fleet, BackupServer server, List<BackupRepository> repositories,
                             List<BackupJob> jobs, Map<MachineId, String> names, String configKey) {

        /** What the kit would say in the clear — the thing whose change means the fleet's copies are wrong. */
        String sheet() {
            return RecoverySheet.render(server, repositories, jobs, names, configKey);
        }
    }

    private KitInputs read() {
        List<Machine> fleet = machines.getAllMachines();
        // The one backup server the fleet is allowed to have; null when none has been designated, which the
        // contents say out loud rather than rendering a page that looks like a recovery plan and is not one.
        BackupServer server = servers.getAll().stream().findFirst().orElse(null);
        return new KitInputs(fleet, server, repositories.getAll(), jobs.getAll(), namesOf(fleet),
            configKey.configKey().orElse(null));
    }

    /** Remember what the fleet is now holding, on disk, so the answer survives a restart of Vaier. */
    private void remember(String fingerprint) {
        VaierConfig fresh = configuration.load().orElseGet(() -> VaierConfig.builder().build());
        configuration.save(fresh.withSurvivalKitFingerprint(fingerprint));
    }

    /**
     * What to call each machine on the page. The stores key on identity, but the kit is read by someone who
     * has just lost their fleet and has to recognise their own machines, so the names travel alongside.
     */
    private static Map<MachineId, String> namesOf(List<Machine> fleet) {
        Map<MachineId, String> names = new LinkedHashMap<>();
        for (Machine machine : fleet) {
            names.putIfAbsent(machine.id(), machine.name());
        }
        return names;
    }
}
