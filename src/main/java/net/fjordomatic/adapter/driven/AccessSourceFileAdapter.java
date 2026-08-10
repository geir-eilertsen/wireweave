package net.fjordomatic.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.AccessSource;
import net.fjordomatic.domain.AccessSources;
import net.fjordomatic.domain.port.ForPersistingAccessSources;
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
 * File-backed store for {@link AccessSource}s: one entry per place in {@code access-sources.yml}, under the
 * root {@code sources:} key. No secrets, so — like {@code DiskWatchFileAdapter} and the backup adapters —
 * a plain, tolerant SnakeYAML round-trip with default file permissions, no {@link SecretCipher}.
 *
 * <p><b>This is not {@code access.yml}.</b> That file, in the same directory, is the social-login store: it
 * holds access entries, which are people's permissions. This one holds places, and grants nobody anything.
 * The names are close enough that they will be confused at a glance, so nothing here ever touches the
 * other file and vice versa.
 *
 * <p>Written wholesale on every save, because pruning removes entries and an append-only store could never
 * let a place expire. An absent file is the healthy first-boot state — nobody has been let in yet.
 *
 * <p>Tolerant on load: an entry that cannot be read is <em>skipped</em> with a warning rather than aborting
 * the load of every other place. The cost of a skipped entry is one place's count, which is a statistic; the
 * cost of an aborted load is the whole map.
 */
@Component
@Slf4j
public class AccessSourceFileAdapter implements ForPersistingAccessSources {

    private static final String FILE_NAME = "access-sources.yml";
    private final String filePath;

    public AccessSourceFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    /** For the test in this package to point the store at a temporary directory — not a wiring seam. */
    AccessSourceFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized AccessSources getAll() {
        File file = new File(filePath);
        if (!file.exists()) return AccessSources.empty();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return AccessSources.empty();
            if (!(data.get("sources") instanceof List<?> list)) return AccessSources.empty();
            List<AccessSource> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    AccessSource source = deserialize(m);
                    if (source != null) result.add(source);
                }
            }
            return AccessSources.of(result);
        } catch (Exception e) {
            log.warn("Failed to load access sources from {}", filePath, e);
            return AccessSources.empty();
        }
    }

    @Override
    public synchronized void save(AccessSources sources) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (AccessSource source : sources.sources()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            // The four location fields are absent together on the unplaceable source, and writing a 0 for
            // an absent coordinate would resurrect it on load as a point in the Atlantic.
            if (source.city() != null) entry.put("city", source.city());
            if (source.country() != null) entry.put("country", source.country());
            if (source.latitude() != null) entry.put("latitude", source.latitude());
            if (source.longitude() != null) entry.put("longitude", source.longitude());
            entry.put("count", source.count());
            entry.put("firstSeen", source.firstSeen().toString());
            entry.put("lastSeen", source.lastSeen().toString());
            entry.put("people", List.copyOf(source.people()));
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("sources", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save access sources to " + filePath, e);
        }
    }

    /**
     * One YAML entry into an {@link AccessSource}, or null when it will not make one. What counts as a
     * usable entry is the record's own call — coordinates absent together, at least one access, both
     * timestamps — not a rule this adapter re-derives; all it does here is map YAML to types and skip
     * whatever the domain refuses.
     */
    private AccessSource deserialize(Map<?, ?> m) {
        try {
            return AccessSource.builder()
                .city(asString(m.get("city")))
                .country(asString(m.get("country")))
                .latitude(asDouble(m.get("latitude")))
                .longitude(asDouble(m.get("longitude")))
                .count(m.get("count") instanceof Number n ? n.longValue() : 0)
                .firstSeen(Instant.parse(asString(m.get("firstSeen"))))
                .lastSeen(Instant.parse(asString(m.get("lastSeen"))))
                .people(asStrings(m.get("people")))
                .build();
        } catch (Exception e) {
            log.warn("Skipping unreadable access-source entry in {}: {}", FILE_NAME, e.toString());
            return null;
        }
    }

    private static List<String> asStrings(Object o) {
        if (!(o instanceof List<?> list)) return List.of();
        List<String> people = new ArrayList<>();
        for (Object person : list) {
            if (person != null) people.add(person.toString());
        }
        return people;
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
