package net.vaier.rest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.vaier.application.WriteSurvivalKitUseCase.SurvivalKitReport;
import net.vaier.domain.BackupJob;
import net.vaier.domain.BackupRepository;
import net.vaier.domain.BackupServer;
import net.vaier.domain.DeviceCategory;
import net.vaier.domain.Machine;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineType;
import net.vaier.domain.SurvivalKit;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForEncryptingSurvivalKits;
import net.vaier.domain.port.ForGeolocatingIps;
import net.vaier.domain.port.ForKeepingSurvivalKits;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForPersistingBackupJobs;
import net.vaier.domain.port.ForPersistingBackupRepositories;
import net.vaier.domain.port.ForPersistingBackupServers;
import net.vaier.domain.port.ForReadingTheConfigKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Writing the kit: the one call behind the operator's only decision here — <em>make sure I can still read my
 * backups if this server is gone</em>.
 *
 * <p>What is under test is the assembly, not the crypto: that the contents reaching each host name the
 * repositories, their passphrases and the config key, that they were locked with the passphrase the operator
 * chose, and that they went to the machines the domain picked and to no others. The envelope itself is
 * proven where it belongs, in {@code OpensslEnvelopeAdapterTest}, against the real {@code openssl} binary.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SurvivalKitWriterTest {

    private static final MachineId APALVEIEN = MachineId.generate();
    private static final MachineId COLINA = MachineId.generate();
    private static final MachineId NAS = MachineId.generate();
    private static final MachineId VAIER_SERVER = MachineId.generate();

    private static final String PASSPHRASE = "one passphrase the operator keeps";

    @Mock ForPersistingBackupServers servers;
    @Mock ForPersistingBackupRepositories repositories;
    @Mock ForPersistingBackupJobs jobs;

    /** Everything written, by destination, so a test can open what a host would actually be holding. */
    private final Map<MachineId, String> kept = new ConcurrentHashMap<>();
    private String localCopy;

    private final ForKeepingSurvivalKits keeper = new ForKeepingSurvivalKits() {
        @Override
        public void keepOn(MachineId machineId, String content) {
            kept.put(machineId, content);
        }

        @Override
        public void keepOnTheVaierServer(String content) {
            localCopy = content;
        }
    };

    /** Reversible stand-in for the OpenSSL envelope, so a test can read back what was locked and under what. */
    private final ForEncryptingSurvivalKits cipher = (plaintext, passphrase) ->
        Base64.getEncoder().encodeToString((passphrase + "\n" + plaintext).getBytes(StandardCharsets.UTF_8));

    private VaierConfig config = VaierConfig.builder()
        .domain("vaier.net")
        .survivalKitPassphrase(PASSPHRASE)
        .build();

    /** Two machines behind two different relays, so the separation rule can choose both. */
    private final List<Machine> machines = new ArrayList<>(List.of(
        peer(APALVEIEN, "Apalveien 5", "88.0.0.1"),
        peer(COLINA, "Colina 27", "77.0.0.1"),
        vaierServer()));

    private static Machine peer(MachineId id, String name, String endpointIp) {
        return new Machine(id, name, MachineType.UBUNTU_SERVER, "key", "10.13.13.2/32", endpointIp, "51820",
            "now", "0", "0", null, null, true, 2375, DeviceCategory.SERVER, null);
    }

    private static Machine vaierServer() {
        return new Machine(VAIER_SERVER, "Vaier server", MachineType.UBUNTU_SERVER, "key", null,
            "52.29.74.114", null, null, null, null, null, null, true, null, DeviceCategory.SERVER, null);
    }

    private final ForPersistingAppConfiguration configStore = new ForPersistingAppConfiguration() {
        @Override
        public Optional<VaierConfig> load() {
            return Optional.ofNullable(config);
        }

        @Override
        public void save(VaierConfig saved) {
            config = saved;
        }

        @Override
        public boolean exists() {
            return config != null;
        }
    };

    private SurvivalKitWriter writer() {
        when(servers.getAll()).thenReturn(List.of(
            new BackupServer("nas-borg", NAS, "192.168.3.3", 8022, "borg", "home/borg/backups",
                "/volume1/docker/borg", false)));
        when(repositories.getAll()).thenReturn(List.of(
            new BackupRepository("apalveien5", "nas-borg", null, "the-apalveien-passphrase", false)));
        when(jobs.getAll()).thenReturn(List.of(
            new BackupJob("apalveien5", APALVEIEN, "apalveien5", List.of("/home"), List.of(),
                7, 4, 6, "zstd,6", true, false)));

        ForReadingTheConfigKey configKey = () -> Optional.of("dGhlLXZhdWx0LWtleQ==");
        ForGeolocatingIps noGeo = ip -> Optional.empty();
        return new SurvivalKitWriter(() -> machines, SurvivalKitWriterTest::vaierServer,
            servers, repositories, jobs, noGeo, cipher, keeper, configStore, configKey,
            Clock.fixed(Instant.parse("2026-07-27T09:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * What a host is holding, opened — the passphrase it was locked with, then the contents. Everything
     * through the first line that is <em>exactly</em> the marker is dropped, which is what the {@code sed}
     * expression on the kit's own face does: the marker also appears mid-line inside that printed command,
     * and cutting there would take the instructions for ciphertext.
     */
    private String openTheKit(String kit) {
        List<String> lines = kit.lines().toList();
        int marker = lines.indexOf(SurvivalKit.BEGIN_MARKER);
        String ciphertext = String.join("\n", lines.subList(marker + 1, lines.size())).strip();
        return new String(Base64.getDecoder().decode(ciphertext), StandardCharsets.UTF_8);
    }

    @Test
    void everyChosenHostEndsUpHoldingAKitThatOpensToTheRepositoryPassphrases() {
        SurvivalKitReport report = writer().writeSurvivalKit();

        assertThat(kept).containsOnlyKeys(APALVEIEN, COLINA);
        assertThat(report.rollout().copiesKept()).isEqualTo(2);
        assertThat(report.rollout().survivesLossOfVaier()).isTrue();

        // The instructions are readable without the passphrase; the contents are not readable without it.
        assertThat(kept.get(APALVEIEN))
            .contains(SurvivalKit.decryptCommand())
            .doesNotContain("the-apalveien-passphrase");

        assertThat(openTheKit(kept.get(APALVEIEN)))
            // Locked with the passphrase the operator chose, not one Vaier invented.
            .startsWith(PASSPHRASE + "\n")
            .contains("the-apalveien-passphrase")
            .contains("ssh://borg@192.168.3.3:8022/home/borg/backups/apalveien5")
            // The machine is named, not identified — a UUID here would be useless to the person reading it.
            .contains("Apalveien 5")
            // And the config key, for the step after: restoring Vaier itself from one of these archives.
            .contains("dGhlLXZhdWx0LWtleQ==");
    }

    /**
     * The likeliest failure is not a lost server but a Vaier that will not start on a host whose disk is
     * fine, so the local copy is written every time — and never counted, because a copy that dies with Vaier
     * is not redundancy.
     */
    @Test
    void theVaierServerGetsACopyOfItsOwn_butIsNeverOneOfTheChosenHosts() {
        SurvivalKitReport report = writer().writeSurvivalKit();

        assertThat(localCopy).isNotNull();
        assertThat(openTheKit(localCopy)).contains("the-apalveien-passphrase");
        assertThat(kept).doesNotContainKey(VAIER_SERVER);
        assertThat(report.selection().chosen()).noneMatch(p -> p.machineId().equals(VAIER_SERVER));
        assertThat(report.rollout().copiesKept()).isEqualTo(2);
    }

    /**
     * No passphrase, no kit — and nothing written anywhere. Vaier will not invent one: a kit it could open by
     * itself dies with it, and an unprotected kit hands every backup in the fleet to whoever picks up a copy.
     */
    @Test
    void refusesToWriteAnythingBeforeTheOperatorHasChosenAPassphrase() {
        config = VaierConfig.builder().domain("vaier.net").build();

        assertThatThrownBy(() -> writer().writeSurvivalKit())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("passphrase");

        assertThat(kept).isEmpty();
        assertThat(localCopy).isNull();
    }

    /** The report says which hosts were passed over and why, so the operator can disagree with a stated reason. */
    @Test
    void theReportCarriesVaiersReasoningForEveryMachineItPassedOver() {
        machines.add(new Machine(MachineId.generate(), "geir-pc", MachineType.WINDOWS_CLIENT, "key", null,
            "88.0.0.1", null, null, null, null, null, null, false, null, DeviceCategory.LAPTOP, null));

        SurvivalKitReport report = writer().writeSurvivalKit();

        assertThat(report.selection().skipped())
            .anySatisfy(skipped -> assertThat(skipped.machineName()).isEqualTo("geir-pc"));
        assertThat(report.selection().skipped()).allSatisfy(
            skipped -> assertThat(skipped.reason()).isNotBlank());
    }
}
