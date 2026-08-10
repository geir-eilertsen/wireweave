package net.fjordomatic.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.DiskPressureBand;
import net.fjordomatic.domain.DiskPressureState;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.port.ForPersistingDiskPressureState;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for {@link DiskPressureState}: one entry per filesystem currently in remote disk
 * pressure in {@code disk-pressure.yml}, keyed on machine id and mount point together, under the root
 * {@code pressure:} key. No secrets, so — like {@link DiskWatchFileAdapter} — a plain, tolerant SnakeYAML
 * round-trip with default file permissions, no {@link SecretCipher}.
 *
 * <p><b>Why on disk at all.</b> This state used to be a field on the scheduled watcher, so every redeploy —
 * several a day — wiped it. That is how the Fjord server's own root filesystem reached 89% against an 80%
 * threshold without a single email: each sweep looked like the first observation of that disk, and a first
 * observation was treated as a silent baseline. An absent file is the healthy first-boot state, not an error.
 *
 * <p>Tolerant on load like the other file adapters, and the tolerance is safe in a way it is not elsewhere:
 * an entry that will not parse is dropped, and a dropped entry means Fjord re-alerts about that filesystem
 * rather than going quiet about it. Losing this state errs towards noise, never towards silence — which is
 * the right direction for a fix whose whole subject is silence.
 */
@Component
@Slf4j
public class DiskPressureStateFileAdapter implements ForPersistingDiskPressureState {

    private static final String FILE_NAME = "disk-pressure.yml";
    private final String filePath;

    public DiskPressureStateFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public DiskPressureStateFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Optional<DiskPressureState> find(MachineId machineId, String mountPoint) {
        // Identity is DiskPressureState.isFor — the domain's rule, not one this adapter re-derives and
        // could drift on.
        return getAll().stream().filter(state -> state.isFor(machineId, mountPoint)).findFirst();
    }

    @Override
    public synchronized void save(DiskPressureState state) {
        List<DiskPressureState> current = new ArrayList<>(getAll());
        current.removeIf(stored -> stored.isFor(state.machineId(), state.mountPoint()));
        current.add(state);
        writeAll(current);
    }

    @Override
    public synchronized void clear(MachineId machineId, String mountPoint) {
        List<DiskPressureState> current = new ArrayList<>(getAll());
        if (current.removeIf(stored -> stored.isFor(machineId, mountPoint))) {
            writeAll(current);
        }
    }

    /** Every stored state. Empty when nothing in the fleet is in pressure — the healthy case. */
    private List<DiskPressureState> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawStates = data.get("pressure");
            if (!(rawStates instanceof List<?> list)) return List.of();
            List<DiskPressureState> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    DiskPressureState state = deserialize(m);
                    if (state != null) result.add(state);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load disk pressure state from {}", filePath, e);
            return List.of();
        }
    }

    /**
     * One YAML entry into a {@link DiskPressureState}, or null when it will not make one. The domain types
     * validate the machine id and the band, so an entry written by an older Fjord — or by hand — that
     * carries a band no code could produce is skipped rather than shifting the escalation ladder.
     */
    private DiskPressureState deserialize(Map<?, ?> m) {
        String rawId = asString(m.get("machineId"));
        String mountPoint = asString(m.get("mountPoint"));
        Object rawBand = m.get("notifiedBandPercent");
        if (!(rawBand instanceof Number band)) {
            log.warn("Skipping disk-pressure entry in {} with no band: {}", FILE_NAME, m);
            return null;
        }
        try {
            return new DiskPressureState(MachineId.of(rawId), mountPoint,
                new DiskPressureBand(band.intValue()));
        } catch (IllegalArgumentException e) {
            log.warn("Skipping unusable disk-pressure entry in {}: {}", FILE_NAME, e.getMessage());
            return null;
        }
    }

    private void writeAll(List<DiskPressureState> states) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (DiskPressureState state : states) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineId", state.machineId().value());
            entry.put("mountPoint", state.mountPoint());
            entry.put("notifiedBandPercent", state.notifiedBand().floorPercent());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("pressure", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save disk pressure state to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
