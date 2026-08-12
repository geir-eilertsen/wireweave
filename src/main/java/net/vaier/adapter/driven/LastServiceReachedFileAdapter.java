package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.LastServiceReached;
import net.vaier.domain.LastServicesReached;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingLastServicesReached;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-backed store of {@link LastServiceReached}es: one entry per machine in
 * {@code last-services-reached.yml}, under the root {@code reached:} key. No secrets — a host name and a
 * timestamp — so a plain SnakeYAML round-trip with default permissions, like {@link AccessSourceFileAdapter}.
 *
 * <p><b>Held in memory, written once a minute.</b> Recording happens inside the check that authenticates
 * every request to every gated service, and a YAML write per page load is not a cost that check may pay.
 * So this holds the collection and {@code flush()} writes it — the same bargain the access sources strike,
 * with the difference that the held collection is also what reads answer, since the peer view has to show
 * a reach that is seconds old.
 *
 * <p>Written wholesale, because an entry is replaced rather than appended. An absent file is the healthy
 * first-boot state — nobody has reached anything yet.
 *
 * <p>Tolerant on load: an entry that will not read is skipped with a warning. The cost is one machine's
 * row; the cost of an aborted load is every machine's.
 */
@Component
@Slf4j
public class LastServiceReachedFileAdapter implements ForPersistingLastServicesReached {

    private static final String FILE_NAME = "last-services-reached.yml";
    private final String filePath;

    /** What has been recorded, loaded from the file on first use. Guarded by this adapter's monitor. */
    private LastServicesReached held;

    /** What the file holds, as far as this instance knows — so an idle minute rewrites nothing. */
    private LastServicesReached written;

    public LastServiceReachedFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    /** For the test in this package to point the store at a temporary directory — not a wiring seam. */
    LastServiceReachedFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized LastServicesReached getAll() {
        if (held == null) {
            held = readFile();
            written = held;
        }
        return held;
    }

    @Override
    public synchronized void save(LastServiceReached reached) {
        held = getAll().with(reached);
    }

    @Override
    public synchronized void flush() {
        if (held == null || held.equals(written)) return;
        writeFile(held);
        written = held;
    }

    private LastServicesReached readFile() {
        File file = new File(filePath);
        if (!file.exists()) return LastServicesReached.empty();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return LastServicesReached.empty();
            if (!(data.get("reached") instanceof List<?> list)) return LastServicesReached.empty();
            List<LastServiceReached> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    LastServiceReached reached = deserialize(m);
                    if (reached != null) result.add(reached);
                }
            }
            return LastServicesReached.of(result);
        } catch (Exception e) {
            log.warn("Failed to load the last services reached from {}", filePath, e);
            return LastServicesReached.empty();
        }
    }

    /**
     * One YAML entry, or null when it will not make one. What counts as a usable entry is the domain's
     * call, not a rule re-derived here.
     */
    private LastServiceReached deserialize(Map<?, ?> m) {
        try {
            return new LastServiceReached(MachineId.of(asString(m.get("machineId"))),
                asString(m.get("host")), Instant.parse(asString(m.get("at"))));
        } catch (Exception e) {
            log.warn("Skipping an unreadable entry in {}: {}", FILE_NAME, e.toString());
            return null;
        }
    }

    private void writeFile(LastServicesReached reached) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (LastServiceReached entry : reached.entries()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("machineId", entry.machineId().value());
            row.put("host", entry.host());
            row.put("at", entry.at().toString());
            serialized.add(row);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("reached", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save the last services reached to " + filePath, e);
        }
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
