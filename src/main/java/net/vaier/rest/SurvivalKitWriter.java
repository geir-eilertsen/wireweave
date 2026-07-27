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
import net.vaier.domain.MachineId;
import net.vaier.domain.SurvivalKit;
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

        List<Machine> fleet = machines.getAllMachines();
        SurvivalKitHosts.Selection selection =
            SurvivalKitHosts.select(fleet, vaierServer.getVaierServerMachine().name(), geo);

        // The one backup server the fleet is allowed to have; null when none has been designated, which the
        // contents say out loud rather than rendering a page that looks like a recovery plan and is not one.
        BackupServer server = servers.getAll().stream().findFirst().orElse(null);

        String kit = SurvivalKit.render(server, repositories.getAll(), jobs.getAll(), namesOf(fleet),
            configKey.configKey().orElse(null), config.getSurvivalKitPassphrase(), clock.instant(), cipher);

        SurvivalKitRollout.Result rollout = SurvivalKitRollout.distribute(selection, kit, keeper);
        if (!rollout.survivesLossOfVaier()) {
            log.warn("Survival kit written, but no fleet machine took a copy — it does not outlive this server");
        } else {
            log.info("Survival kit written to {} fleet machine(s), {} refused",
                rollout.copiesKept(), rollout.failures().size());
        }
        return new SurvivalKitReport(selection, rollout);
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
