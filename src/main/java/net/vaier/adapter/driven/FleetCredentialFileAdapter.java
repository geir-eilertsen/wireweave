package net.vaier.adapter.driven;

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
import net.vaier.domain.FleetCredential;
import net.vaier.domain.port.ForPersistingFleetCredentials;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * File-backed store for fleet credentials: {@code fleet-credentials.yml}, keyed on the credential's
 * name. The {@code content} is sealed at rest with the vault's existing {@link SecretCipher}
 * ({@code enc:v1:} envelope) — no second cipher and no second vault; the name, path, mode and the
 * {@code distributed} flag are stored in the clear so the file stays legible to whoever has to read it
 * during a recovery.
 *
 * <p>The {@code distributed} flag is on disk deliberately. It is what licenses the background reconcile
 * to heal a credential, and a latch living in a field is wiped by every redeploy — the exact bug that
 * silenced the disk alerts for months.
 */
@Component
@Slf4j
public class FleetCredentialFileAdapter implements ForPersistingFleetCredentials {

    private static final String FILE_NAME = "fleet-credentials.yml";
    private final String filePath;
    private final SecretCipher cipher;

    public FleetCredentialFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"), new SecretCipher());
    }

    public FleetCredentialFileAdapter(String configDir, SecretCipher cipher) {
        this.filePath = configDir + "/" + FILE_NAME;
        this.cipher = cipher;
    }

    @Override
    public synchronized List<FleetCredential> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object raw = data.get("credentials");
            if (!(raw instanceof List<?> list)) return List.of();
            List<FleetCredential> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    FleetCredential credential = deserialize(m);
                    if (credential != null) result.add(credential);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load fleet credentials from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized Optional<FleetCredential> getByName(String name) {
        return getAll().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    @Override
    public synchronized void save(FleetCredential credential) {
        List<FleetCredential> current = new ArrayList<>(getAll());
        current.removeIf(c -> c.name().equals(credential.name()));
        current.add(credential);
        writeAll(current);
    }

    @Override
    public synchronized void deleteByName(String name) {
        List<FleetCredential> current = new ArrayList<>(getAll());
        if (current.removeIf(c -> c.name().equals(name))) {
            writeAll(current);
        }
    }

    /**
     * One stored entry back into a credential, or null when it cannot be one. Tolerant on purpose, like
     * the backup adapters: one unreadable entry must never cost the operator every other credential in
     * the file — and a credential Vaier cannot reconstruct exactly is one it must not distribute at all.
     */
    private FleetCredential deserialize(Map<?, ?> m) {
        try {
            return new FleetCredential(
                asString(m.get("name")),
                asString(m.get("targetPath")),
                asString(m.get("mode")),
                cipher.decrypt(asString(m.get("content"))),
                m.get("distributed") instanceof Boolean b && b);
        } catch (RuntimeException e) {
            log.warn("Skipping unusable fleet-credential entry in {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    private void writeAll(List<FleetCredential> credentials) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (FleetCredential c : credentials) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", c.name());
            entry.put("targetPath", c.targetPath());
            entry.put("mode", c.mode());
            entry.put("content", cipher.encrypt(c.content()));
            entry.put("distributed", c.distributed());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("credentials", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save fleet credentials to " + filePath, e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
