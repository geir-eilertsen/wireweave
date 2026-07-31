package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.SourceAddress;
import net.vaier.domain.port.ForPersistingTrustedAddresses;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * File-backed store for the addresses the operator has permanently trusted (#329 Slice 3c): one entry per
 * address in {@code trusted-addresses.yml}, under the root {@code addresses:} key, beside its siblings
 * {@code access.yml}, {@code disk-watches.yml} and {@code lan-servers.yml} under {@code VAIER_CONFIG_PATH}.
 * No secrets, so — like {@link DiskWatchFileAdapter} — a plain, tolerant SnakeYAML round-trip with default
 * file permissions and no {@link SecretCipher}.
 *
 * <p><b>Why this file exists at all.</b> CrowdSec's whitelist parser file is <em>derived</em>:
 * {@link CrowdSecWhitelistFileAdapter} rewrites it wholesale from {@code TrustedNetworks.allCidrs()} every
 * five minutes. An address appended to that file directly is therefore erased within five minutes. This is
 * where the operator's decision actually lives, and the rewrite reads from it.
 *
 * <p>Tolerant on load, like every sibling store: a missing file is the healthy first-boot state and reads
 * as an empty list, and a row that will not make a {@link SourceAddress} is skipped with a warning rather
 * than costing every other trusted address its place.
 */
@Component
@Slf4j
public class TrustedAddressFileAdapter implements ForPersistingTrustedAddresses {

    private static final String FILE_NAME = "trusted-addresses.yml";
    private static final String ROOT_KEY = "addresses";
    private static final String ADDRESS_KEY = "address";

    private final String filePath;

    public TrustedAddressFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    TrustedAddressFileAdapter(String configDir) {
        this.filePath = configDir + "/" + FILE_NAME;
    }

    @Override
    public synchronized List<SourceAddress> getAll() {
        File file = new File(filePath);
        if (!file.exists()) return List.of();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return List.of();
            Object rawAddresses = data.get(ROOT_KEY);
            if (!(rawAddresses instanceof List<?> list)) return List.of();
            List<SourceAddress> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    SourceAddress address = deserialize(m);
                    if (address != null) result.add(address);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to load trusted addresses from {}", filePath, e);
            return List.of();
        }
    }

    @Override
    public synchronized void save(SourceAddress address) {
        List<SourceAddress> current = new ArrayList<>(getAll());
        // A trusted address is its own identity — trusting it twice must not grow the file a row at a time.
        if (current.contains(address)) return;
        current.add(address);
        writeAll(current);
    }

    /**
     * One YAML row into a {@link SourceAddress}, or null when it will not make one. {@code SourceAddress.of}
     * applies the dotted-quad-only rule; a row written by an older Vaier or by hand that carries anything
     * else is skipped with a warning. Loud enough to see, not fatal: the remaining addresses stay trusted.
     */
    private SourceAddress deserialize(Map<?, ?> m) {
        Object raw = m.get(ADDRESS_KEY);
        try {
            return SourceAddress.of(raw == null ? null : raw.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping an unusable entry in {}: {}", FILE_NAME, e.getMessage());
            return null;
        }
    }

    private void writeAll(List<SourceAddress> addresses) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (SourceAddress address : addresses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(ADDRESS_KEY, address.value());
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(ROOT_KEY, serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save trusted addresses to " + filePath, e);
        }
    }
}
