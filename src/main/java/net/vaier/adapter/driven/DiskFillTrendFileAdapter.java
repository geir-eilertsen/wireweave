package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DiskFillSample;
import net.vaier.domain.DiskFillTrend;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingDiskFillTrends;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for {@link DiskFillTrend}s: one entry per sampled filesystem in
 * {@code disk-fill-trend.yml}, keyed on machine id and mount point together, under the root {@code trends:}
 * key. No secrets, so — like {@link DiskPressureStateFileAdapter} — a plain, tolerant SnakeYAML round-trip
 * with default file permissions, no {@link SecretCipher}.
 *
 * <p><b>Why on disk at all.</b> The forecast projects from a week of samples, and this project redeploys
 * several times a day. Held in a field on the scheduled watcher — where it was — the history could never
 * grow past one deploy's uptime, so no forecast was ever produced. The already-warned latch rides along so a
 * redeploy also cannot re-page about a disk admins have already been told about.
 *
 * <p>Tolerant on load: an entry or a sample that will not parse is dropped and logged rather than being
 * fatal, and an absent file is the healthy first-boot state. Losing a trend costs a week of rebuilt baseline,
 * never a warning that was owed.
 */
@Component
@Slf4j
public class DiskFillTrendFileAdapter implements ForPersistingDiskFillTrends {

    private static final String FILE_NAME = "disk-fill-trend.yml";
    private final String filePath;

    public DiskFillTrendFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public DiskFillTrendFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Optional<DiskFillTrend> find(MachineId machineId, String mountPoint) {
        // Identity is DiskFillTrend.isFor — the domain's rule, not one this adapter re-derives.
        return getAll().stream().filter(trend -> trend.isFor(machineId, mountPoint)).findFirst();
    }

    @Override
    public synchronized void save(DiskFillTrend trend) {
        List<DiskFillTrend> current = new ArrayList<>(getAll());
        current.removeIf(stored -> stored.isFor(trend.machineId(), trend.mountPoint()));
        current.add(trend);
        writeAll(current);
    }

    @Override
    public synchronized void retainOnly(Set<MachineId> machineIds) {
        List<DiskFillTrend> current = new ArrayList<>(getAll());
        if (current.removeIf(trend -> machineIds.stream().noneMatch(trend.machineId()::isSameAs))) {
            writeAll(current);
        }
    }

    /** Every stored trend. Empty when nothing has been sampled yet — the first-boot state. */
    private List<DiskFillTrend> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawTrends = data.get("trends");
            if (!(rawTrends instanceof List<?> list)) return List.of();
            List<DiskFillTrend> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    DiskFillTrend trend = deserialize(m);
                    if (trend != null) result.add(trend);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load disk fill trends from {}", filePath, e);
            return List.of();
        }
    }

    /** One YAML entry into a {@link DiskFillTrend}, or null when it will not make one. */
    private DiskFillTrend deserialize(Map<?, ?> m) {
        try {
            List<DiskFillSample> samples = new ArrayList<>();
            if (m.get("samples") instanceof List<?> rawSamples) {
                for (Object rawSample : rawSamples) {
                    if (rawSample instanceof Map<?, ?> sample) {
                        DiskFillSample parsed = deserializeSample(sample);
                        if (parsed != null) samples.add(parsed);
                    }
                }
            }
            return new DiskFillTrend(MachineId.of(asString(m.get("machineId"))),
                asString(m.get("mountPoint")), samples, Boolean.TRUE.equals(m.get("warned")));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping unusable disk-fill-trend entry in {}: {}", FILE_NAME, e.getMessage());
            return null;
        }
    }

    private DiskFillSample deserializeSample(Map<?, ?> sample) {
        try {
            if (!(sample.get("availableKb") instanceof Number availableKb)) {
                log.warn("Skipping disk-fill sample in {} with no free space: {}", FILE_NAME, sample);
                return null;
            }
            return new DiskFillSample(Instant.parse(asString(sample.get("at"))), availableKb.longValue());
        } catch (IllegalArgumentException | DateTimeParseException | NullPointerException e) {
            log.warn("Skipping unusable disk-fill sample in {}: {}", FILE_NAME, sample);
            return null;
        }
    }

    private void writeAll(List<DiskFillTrend> trends) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (DiskFillTrend trend : trends) {
            List<Map<String, Object>> samples = new ArrayList<>();
            for (DiskFillSample sample : trend.samples()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("at", sample.at().toString());
                entry.put("availableKb", sample.availableKb());
                samples.add(entry);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineId", trend.machineId().value());
            entry.put("mountPoint", trend.mountPoint());
            entry.put("warned", trend.warned());
            entry.put("samples", samples);
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("trends", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save disk fill trends to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
