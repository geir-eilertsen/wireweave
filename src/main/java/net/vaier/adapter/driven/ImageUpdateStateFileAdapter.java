package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.ScopedImage;
import net.vaier.domain.port.ForPersistingImageUpdateState;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for the <b>update available</b> latch: one entry per {@link ScopedImage} Vaier currently
 * knows to be out of date, in {@code update-available.yml} under the root {@code updateAvailable:} key. No
 * secrets, so — like {@link DiskPressureStateFileAdapter} — a plain, tolerant SnakeYAML round-trip with
 * default file permissions and no {@link SecretCipher}.
 *
 * <p><b>Why on disk at all.</b> The latch was a field on a bean built with a bare constructor, wiped by every
 * restart — several a day here — so each boot's first sweep re-announced every image that was still out of
 * date. An absent file is the first-boot state, not an error.
 *
 * <p>Tolerant on load, and the direction the tolerance errs in is deliberate: an entry that will not parse is
 * dropped, and a dropped entry costs one duplicate mail about an image that really is out of date. Throwing
 * would instead kill the sweep and every alert in it. Noise over silence, as everywhere in this feature.
 */
@Component
@Slf4j
public class ImageUpdateStateFileAdapter implements ForPersistingImageUpdateState {

    private static final String FILE_NAME = "update-available.yml";
    private static final String ROOT_KEY = "updateAvailable";
    private final String filePath;

    public ImageUpdateStateFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public ImageUpdateStateFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized Set<ScopedImage> loadOutOfDate() {
        File file = new File(filePath);
        if (!file.exists()) return Set.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return Set.of();
            if (!(data.get(ROOT_KEY) instanceof List<?> list)) return Set.of();
            Set<ScopedImage> images = new LinkedHashSet<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    ScopedImage image = deserialize(m);
                    if (image != null) images.add(image);
                }
            }
            return images;
        } catch (Exception e) {
            log.warn("Failed to load update-available state from {}", filePath, e);
            return Set.of();
        }
    }

    @Override
    public synchronized void saveOutOfDate(Set<ScopedImage> images) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (ScopedImage image : images) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineId", image.machineId());
            entry.put("image", image.image());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ROOT_KEY, serialized);

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
