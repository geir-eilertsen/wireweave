package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.TrustedNetworks;
import net.vaier.domain.port.ForWritingCrowdSecWhitelist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders {@link TrustedNetworks} into CrowdSec's whitelist-parser YAML schema (verified against
 * the real {@code crowdsecurity/whitelists} hub file), so the operator's own VPN subnet, Docker
 * bridge and relay LANs are never subject to a block decision. Bind-mounted into the crowdsec
 * container's {@code parsers/s02-enrich} stage directory — CrowdSec picks up a changed parser
 * file on its next restart, not live (see PRD §6.26).
 */
@Component
@Slf4j
public class CrowdSecWhitelistFileAdapter implements ForWritingCrowdSecWhitelist {

    private final String filePath;

    public CrowdSecWhitelistFileAdapter(
        @Value("${crowdsec.whitelist.path:/crowdsec-whitelist/vaier-trusted-networks.yaml}") String filePath) {
        this.filePath = filePath;
    }

    @Override
    public synchronized void write(TrustedNetworks trustedNetworks) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        Map<String, Object> whitelist = new LinkedHashMap<>();
        whitelist.put("reason", "vaier trusted networks");
        whitelist.put("cidr", trustedNetworks.allCidrs());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "vaier/trusted-networks");
        root.put("description",
            "Vaier operator's own networks (VPN subnet, Docker bridge, relay LANs) — never blocked (#329)");
        root.put("whitelist", whitelist);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write CrowdSec whitelist to " + filePath, e);
        }
        log.info("Wrote CrowdSec trusted-networks whitelist ({} CIDRs) to {}",
            trustedNetworks.allCidrs().size(), filePath);
    }
}
