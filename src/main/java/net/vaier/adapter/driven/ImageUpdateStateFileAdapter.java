package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.RegistryDigestHistory;
import net.vaier.domain.RegistryDigestHistory.Answer;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.port.ForPersistingImageUpdateState;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for what Vaier must remember between <b>update sweeps</b>, in
 * {@code update-available.yml}. Two sections, because two questions outlive a sweep: the <b>update
 * available</b> latch under {@code updateAvailable:} — one entry per {@link ScopedImage} currently known to
 * be out of date — and, under {@code registryDigests:}, the last few distinct digests each image's registry
 * served with the instant each was first seen, which is what tells a <b>moving tag</b> from one that has
 * settled. No secrets, so — like
 * {@link DiskPressureStateFileAdapter} — a plain, tolerant SnakeYAML round-trip with default file
 * permissions and no {@link SecretCipher}.
 *
 * <p><b>Why on disk at all.</b> The latch was a field on a bean built with a bare constructor, wiped by every
 * restart — several a day here — so each boot's first sweep re-announced every image that was still out of
 * date. The digest history is on disk for the same reason twice over: it takes three daily answers to
 * recognise a channel, and a history that died with the process would never reach three.
 *
 * <p><b>Each section is saved without disturbing the other.</b> Both are written on every sweep, so a save
 * that dumped only its own key would silently drop the other's — which for the latch means a fleet-wide
 * re-announcement and for the history means the nightly suppression never engaging.
 *
 * <p>Tolerant on load, and the direction the tolerance errs in is deliberate: an entry that will not parse is
 * dropped, and a dropped entry costs one duplicate mail about an image that really is out of date. Throwing
 * would instead kill the sweep and every alert in it. Noise over silence, as everywhere in this feature.
 */
@Component
@Slf4j
public class ImageUpdateStateFileAdapter implements ForPersistingImageUpdateState {

    private static final String FILE_NAME = "update-available.yml";
    private static final String OUT_OF_DATE_KEY = "updateAvailable";
    private static final String REGISTRY_DIGESTS_KEY = "registryDigests";
    private final String filePath;

    public ImageUpdateStateFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public ImageUpdateStateFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Set<ScopedImage> loadOutOfDate() {
        if (!(document().get(OUT_OF_DATE_KEY) instanceof List<?> list)) return Set.of();
        Set<ScopedImage> images = new LinkedHashSet<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> m) {
                ScopedImage image = deserialize(m);
                if (image != null) images.add(image);
            }
        }
        return images;
    }

    @Override
    public synchronized void saveOutOfDate(Set<ScopedImage> images) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ScopedImage image : images) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineId", image.machineId());
            entry.put("image", image.image());
            serialized.add(entry);
        }
        save(OUT_OF_DATE_KEY, serialized);
    }

    @Override
    public synchronized RegistryDigestHistory loadRegistryDigestHistory() {
        if (!(document().get(REGISTRY_DIGESTS_KEY) instanceof Map<?, ?> section)) {
            return RegistryDigestHistory.empty();
        }
        Map<String, List<Answer>> answers = new LinkedHashMap<>();
        section.forEach((image, entries) -> {
            if (image == null || !(entries instanceof List<?> list)) {
                log.warn("Skipping unusable registry-digest history for {} in {}", image, FILE_NAME);
                return;
            }
            List<Answer> read = new ArrayList<>();
            for (Object entry : list) {
                Answer answer = deserializeAnswer(entry);
                if (answer != null) read.add(answer);
            }
            if (!read.isEmpty()) answers.put(image.toString(), read);
        });
        return new RegistryDigestHistory(answers);
    }

    @Override
    public synchronized void saveRegistryDigestHistory(RegistryDigestHistory history) {
        Map<String, Object> section = new LinkedHashMap<>();
        history.answers().forEach((image, entries) -> {
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (Answer answer : entries) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("digest", answer.digest());
                entry.put("firstSeen", answer.firstSeen().toString());
                serialized.add(entry);
            }
            section.put(image, serialized);
        });
        save(REGISTRY_DIGESTS_KEY, section);
    }

    /**
     * One YAML entry into an {@link Answer}, or null when it will not make one. An unreadable instant is
     * dropped rather than dated to now: "now" would invent a change that has just happened, which is
     * precisely what makes a tag read as moving.
     */
    private Answer deserializeAnswer(Object entry) {
        if (entry instanceof Map<?, ?> m) {
            String digest = asString(m.get("digest"));
            String firstSeen = asString(m.get("firstSeen"));
            if (!isBlank(digest) && !isBlank(firstSeen)) {
                try {
                    return new Answer(digest, Instant.parse(firstSeen));
                } catch (DateTimeParseException e) {
                    // Falls through to the warning below.
                }
            }
        }
        log.warn("Skipping unusable registry-digest answer in {}: {}", FILE_NAME, entry);
        return null;
    }

    /** The whole file as YAML gave it, or an empty document when there is none Vaier can read. */
    private Map<String, Object> document() {
        File file = new File(filePath);
        if (!file.exists()) return Map.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            return data == null ? Map.of() : data;
        } catch (Exception e) {
            log.warn("Failed to load update-available state from {}", filePath, e);
            return Map.of();
        }
    }

    /** Write {@code section} under {@code key}, keeping every other section exactly as it was. */
    private void save(String key, Object section) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        Map<String, Object> root = new LinkedHashMap<>(document());
        root.put(key, section);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try (FileWriter writer = new FileWriter(file)) {
            new Yaml(options).dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save update-available state to " + filePath, e);
        }
    }

    /** One YAML entry into a {@link ScopedImage}, or null when it will not make one. */
    private ScopedImage deserialize(Map<?, ?> m) {
        String machineId = asString(m.get("machineId"));
        String image = asString(m.get("image"));
        if (isBlank(machineId) || isBlank(image)) {
            log.warn("Skipping unusable update-available entry in {}: {}", FILE_NAME, m);
            return null;
        }
        return new ScopedImage(machineId, image);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
