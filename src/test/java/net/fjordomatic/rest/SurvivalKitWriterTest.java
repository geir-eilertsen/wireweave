package net.fjordomatic.rest;

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
import net.fjordomatic.application.WriteSurvivalKitUseCase.SurvivalKitReport;
import net.fjordomatic.domain.BackupJob;
import net.fjordomatic.domain.BackupRepository;
import net.fjordomatic.domain.BackupServer;
import net.fjordomatic.domain.DeviceCategory;
import net.fjordomatic.domain.Machine;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineType;
import net.fjordomatic.domain.SurvivalKit;
import net.fjordomatic.domain.FjordConfig;
import net.fjordomatic.domain.port.ForEncryptingSurvivalKits;
import net.fjordomatic.domain.port.ForGeolocatingIps;
import net.fjordomatic.domain.port.ForKeepingSurvivalKits;
import net.fjordomatic.domain.port.ForPersistingAppConfiguration;
import net.fjordomatic.domain.port.ForPersistingBackupJobs;
import net.fjordomatic.domain.port.ForPersistingBackupRepositories;
import net.fjordomatic.domain.port.ForPersistingBackupServers;
import net.fjordomatic.domain.port.ForReadingTheConfigKey;
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
    private static final MachineId FJORD_SERVER = MachineId.generate();

    private static final String PASSPHRASE = "one passphrase the operator keeps";

    @Mock ForPersistingBackupServers servers;
    @Mock ForPersistingBackupRepositories repositories;
    @Mock ForPersistingBackupJobs jobs;

    /** Everything written, by destination, so a test can open what a host would actually be holding. */
    private final Map<MachineId, String> kept = new ConcurrentHashMap<>();
    private String localCopy;

    /** Hosts that will not take a copy this time — a machine that is off, in one line. */
    private final List<MachineId> refusing = new ArrayList<>();

    private final ForKeepingSurvivalKits keeper = new ForKeepingSurvivalKits() {
        @Override
        public void keepOn(MachineId machineId, String content) {
            if (refusing.contains(machineId)) {
                throw new IllegalStateException("ssh: connect to host: no route to host");
            }
            kept.put(machineId, content);
        }

        @Override
        public void keepOnTheFjordServer(String content) {
            localCopy = content;
        }
    };

    /** Reversible stand-in for the OpenSSL envelope, so a test can read back what was locked and under what. */
    private final ForEncryptingSurvivalKits cipher = (plaintext, passphrase) ->
        Base64.getEncoder().encodeToString((passphrase + "\n" + plaintext).getBytes(StandardCharsets.UTF_8));

    private FjordConfig config = FjordConfig.builder()
        .domain("vaier.net")
        .survivalKitPassphrase(PASSPHRASE)
        .build();

    /** Two machines behind two different relays, so the separation rule can choose both. */
    private final List<Machine> machines = new ArrayList<>(List.of(
        peer(APALVEIEN, "Apalveien 5", "88.0.0.1"),
        peer(COLINA, "Colina 27", "77.0.0.1"),
        fjordServer()));

    private static Machine peer(MachineId id, String name, String endpointIp) {
        return new Machine(id, name, MachineType.UBUNTU_SERVER, "key", "10.13.13.2/32", endpointIp, "51820",
            "now", "0", "0", null, null, true, 2375, DeviceCategory.SERVER, null);
    }

    private static Machine fjordServer() {
        return new Machine(FJORD_SERVER, "Fjord server", MachineType.UBUNTU_SERVER, "key", null,
            "52.29.74.114", null, null, null, null, null, null, true, null, DeviceCategory.SERVER, null);
    }

    private final ForPersistingAppConfiguration configStore = new ForPersistingAppConfiguration() {
        @Override
        public Optional<FjordConfig> load() {
            return Optional.ofNullable(config);
        }

        @Override
        public void save(FjordConfig saved) {
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
        return new SurvivalKitWriter(() -> machines, SurvivalKitWriterTest::fjordServer,
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
        assertThat(report.rollout().survivesLossOfFjord()).isTrue();

        // The instructions are readable without the passphrase; the contents are not readable without it.
        assertThat(kept.get(APALVEIEN))
            .contains(SurvivalKit.decryptCommand())
            .doesNotContain("the-apalveien-passphrase");

        assertThat(openTheKit(kept.get(APALVEIEN)))
            // Locked with the passphrase the operator chose, not one Fjord invented.
            .startsWith(PASSPHRASE + "\n")
            .contains("the-apalveien-passphrase")
            .contains("ssh://borg@192.168.3.3:8022/home/borg/backups/apalveien5")
            // The machine is named, not identified — a UUID here would be useless to the person reading it.
            .contains("Apalveien 5")
            // And the config key, for the step after: restoring Fjord itself from one of these archives.
            .contains("dGhlLXZhdWx0LWtleQ==");
    }

    /**
     * The likeliest failure is not a lost server but a Fjord that will not start on a host whose disk is
     * fine, so the local copy is written every time — and never counted, because a copy that dies with Fjord
     * is not redundancy.
     */
    @Test
    void theFjordServerGetsACopyOfItsOwn_butIsNeverOneOfTheChosenHosts() {
        SurvivalKitReport report = writer().writeSurvivalKit();

        assertThat(localCopy).isNotNull();
        assertThat(openTheKit(localCopy)).contains("the-apalveien-passphrase");
        assertThat(kept).doesNotContainKey(FJORD_SERVER);
        assertThat(report.selection().chosen()).noneMatch(p -> p.machineId().equals(FJORD_SERVER));
        assertThat(report.rollout().copiesKept()).isEqualTo(2);
    }

    /**
     * No passphrase, no kit — and nothing written anywhere. Fjord will not invent one: a kit it could open by
     * itself dies with it, and an unprotected kit hands every backup in the fleet to whoever picks up a copy.
     */
    @Test
    void refusesToWriteAnythingBeforeTheOperatorHasChosenAPassphrase() {
        config = FjordConfig.builder().domain("vaier.net").build();

        assertThatThrownBy(() -> writer().writeSurvivalKit())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("passphrase");

        assertThat(kept).isEmpty();
        assertThat(localCopy).isNull();
    }

    /** The report says which hosts were passed over and why, so the operator can disagree with a stated reason. */
    @Test
    void theReportCarriesFjordsReasoningForEveryMachineItPassedOver() {
        machines.add(new Machine(MachineId.generate(), "geir-pc", MachineType.WINDOWS_CLIENT, "key", null,
            "88.0.0.1", null, null, null, null, null, null, false, null, DeviceCategory.LAPTOP, null));

        SurvivalKitReport report = writer().writeSurvivalKit();

        assertThat(report.selection().skipped())
            .anySatisfy(skipped -> assertThat(skipped.machineName()).isEqualTo("geir-pc"));
        assertThat(report.selection().skipped()).allSatisfy(
            skipped -> assertThat(skipped.reason()).isNotBlank());
    }

    // --- keeping the fleet's kits current, unasked ------------------------------------------------------
    //
    // The button is not the feature. A kit rewritten only when someone remembers has the same flaw as the
    // printed sheet it replaced: it goes stale the moment a passphrase changes, and you believe you are
    // covered. These are the sweeps that make it Fjord's problem instead of the operator's.

    @Test
    void theFirstSweepWritesTheFleet_becauseNeverWrittenIsTheStalenessThatMattersMost() {
        writer().keepTheFleetsKitsCurrent();

        assertThat(kept).containsOnlyKeys(APALVEIEN, COLINA);
        // What was written is remembered, so the next sweep has something to compare against.
        assertThat(config.getSurvivalKitFingerprint()).isNotBlank();
    }

    @Test
    void aSweepThatFindsNothingChangedWritesNothing() {
        SurvivalKitWriter writer = writer();
        writer.keepTheFleetsKitsCurrent();
        kept.clear();
        localCopy = null;

        writer.keepTheFleetsKitsCurrent();

        assertThat(kept).isEmpty();
        assertThat(localCopy).isNull();
    }

    /**
     * The cause the operator would most expect to be covered, and the one a fingerprint of the contents
     * cannot see on its own: the kit says exactly the same words, it is only locked differently. Every copy
     * on the fleet still opens with the old passphrase until this happens.
     */
    @Test
    void changingTheKitPassphraseRewritesTheFleetOnTheNextSweep() {
        SurvivalKitWriter writer = writer();
        writer.keepTheFleetsKitsCurrent();
        kept.clear();

        config = config.withSurvivalKitPassphrase("a different passphrase");
        writer.keepTheFleetsKitsCurrent();

        assertThat(kept).containsOnlyKeys(APALVEIEN, COLINA);
        assertThat(openTheKit(kept.get(APALVEIEN))).startsWith("a different passphrase\n");
    }

    /** A repository added, a passphrase rotated, a machine renamed — all of it reaches the kit as contents. */
    @Test
    void aChangeToWhatTheKitWouldSayRewritesTheFleet() {
        SurvivalKitWriter writer = writer();
        writer.keepTheFleetsKitsCurrent();
        kept.clear();

        when(repositories.getAll()).thenReturn(List.of(
            new BackupRepository("apalveien5", "nas-borg", null, "a-rotated-passphrase", false)));
        writer.keepTheFleetsKitsCurrent();

        assertThat(openTheKit(kept.get(COLINA))).contains("a-rotated-passphrase");
    }

    /**
     * A host that was asleep holds nothing, or holds something older. Nothing about the contents changed, so
     * no other signal would ever notice — and the fleet would sit a copy short of what it believes it has.
     */
    @Test
    void aHostThatMissedTheLastWriteIsTriedAgainOnTheNextSweep() {
        SurvivalKitWriter writer = writer();
        refusing.add(COLINA);
        writer.keepTheFleetsKitsCurrent();
        assertThat(kept).containsOnlyKeys(APALVEIEN);

        refusing.clear();
        kept.clear();
        writer.keepTheFleetsKitsCurrent();

        assertThat(kept).containsOnlyKeys(APALVEIEN, COLINA);
    }

    /** No passphrase, nothing written — the sweep is not a way around the operator's one decision. */
    @Test
    void aSweepWritesNothingBeforeAPassphraseHasBeenChosen() {
        config = FjordConfig.builder().domain("vaier.net").build();

        writer().keepTheFleetsKitsCurrent();

        assertThat(kept).isEmpty();
        assertThat(localCopy).isNull();
    }

    /**
     * Nothing is being backed up, so there is nothing to survive. Pushing a kit onto three machines to tell
     * them so is noise, and it would put a file on every host of a fleet that has not asked for any of this.
     */
    @Test
    void aSweepWritesNothingWhenNoRepositoryExistsYet() {
        SurvivalKitWriter writer = writer();
        when(repositories.getAll()).thenReturn(List.of());

        writer.keepTheFleetsKitsCurrent();

        assertThat(kept).isEmpty();
        assertThat(config.getSurvivalKitFingerprint()).isNull();
    }

    /** A hand-pressed write records what it wrote too, or the very next sweep would write it all again. */
    @Test
    void writingByHandRecordsWhatWasWritten_soTheNextSweepDoesNotRepeatIt() {
        SurvivalKitWriter writer = writer();
        writer.writeSurvivalKit();
        String recorded = config.getSurvivalKitFingerprint();
        kept.clear();

        writer.keepTheFleetsKitsCurrent();

        assertThat(recorded).isNotBlank();
        assertThat(kept).isEmpty();
    }

    /** The sweep never throws: it runs on a timer, and a scheduler that dies stops watching everything. */
    @Test
    void aSweepThatCannotReadTheFleetDoesNotThrow() {
        SurvivalKitWriter writer = writer();
        when(servers.getAll()).thenThrow(new IllegalStateException("the store is unreadable"));

        writer.keepTheFleetsKitsCurrent();

        assertThat(kept).isEmpty();
    }
}
