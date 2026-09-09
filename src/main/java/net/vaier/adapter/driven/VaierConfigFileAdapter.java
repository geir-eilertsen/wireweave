package net.vaier.adapter.driven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@Component
@Slf4j
public class VaierConfigFileAdapter implements ForPersistingAppConfiguration, ForReadingStoredSmtpPassword {

    private static final String CONFIG_FILE_NAME = "vaier-config.yml";
    private final String configFilePath;
    private final SecretCipher cipher;

    public VaierConfigFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"));
    }

    public VaierConfigFileAdapter(String configDir) {
        this(configDir, new SecretCipher(configDir));
    }

    public VaierConfigFileAdapter(String configDir, SecretCipher cipher) {
        this.configFilePath = configDir + "/" + CONFIG_FILE_NAME;
        this.cipher = cipher;
    }

    @Override
    public Optional<VaierConfig> load() {
        File file = new File(configFilePath);
        if (!file.exists()) {
            return Optional.empty();
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(fis);
            if (data == null) {
                return Optional.empty();
            }
            return Optional.of(VaierConfig.builder()
                // Only the keys named here are read, so a stale key left over from an older Vaier —
                // awsKey/awsSecret on any install that predates #331 — is ignored rather than fatal.
                .domain((String) data.get("domain"))
                .acmeEmail((String) data.get("acmeEmail"))
                .smtpHost((String) data.get("smtpHost"))
                .smtpPort((Integer) data.get("smtpPort"))
                .smtpUsername((String) data.get("smtpUsername"))
                .smtpSender((String) data.get("smtpSender"))
                // smtpPassword and survivalKitPassphrase are encrypted at rest (#307). decrypt() passes
                // legacy plaintext through unchanged, so a pre-#307 config still loads (and re-encrypts on save).
                .smtpPassword(cipher.decrypt((String) data.get("smtpPassword")))
                .survivalKitPassphrase(cipher.decrypt((String) data.get("survivalKitPassphrase")))
                .survivalKitFingerprint((String) data.get("survivalKitFingerprint"))
                .anthropicApiKey(cipher.decrypt((String) data.get("anthropicApiKey")))
                .diskMonitorThresholdPercent((Integer) data.get("diskMonitorThresholdPercent"))
                .backupScheduleHour((Integer) data.get("backupScheduleHour"))
                .vaierServerSshAccess((Boolean) data.get("vaierServerSshAccess"))
                .vaierServerMachineId((String) data.get("vaierServerMachineId"))
                .build());
        } catch (Exception e) {
            log.warn("Failed to load vaier config from {}", configFilePath, e);
            return Optional.empty();
        }
    }

    @Override
    public void save(VaierConfig config) {
        File file = new File(configFilePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("domain", config.getDomain());
        data.put("acmeEmail", config.getAcmeEmail());
        data.put("smtpHost", config.getSmtpHost());
        data.put("smtpPort", config.getSmtpPort());
        data.put("smtpUsername", config.getSmtpUsername());
        data.put("smtpSender", config.getSmtpSender());
        // Encrypt the reversible secrets at rest (#307); encrypt() returns null for a null input.
        data.put("smtpPassword", cipher.encrypt(config.getSmtpPassword()));
        // The survival kit passphrase — the third reversible secret here, encrypted like the other two.
        data.put("survivalKitPassphrase", cipher.encrypt(config.getSurvivalKitPassphrase()));
        // What the kits on the fleet say, so a sweep can tell whether they still say it. Not a secret: a
        // digest of a page whose every line is already in this file, and it must be readable to be compared.
        data.put("survivalKitFingerprint", config.getSurvivalKitFingerprint());
        // The Anthropic API key — the fourth reversible secret here, encrypted like the rest (#360).
        data.put("anthropicApiKey", cipher.encrypt(config.getAnthropicApiKey()));
        data.put("diskMonitorThresholdPercent", config.getDiskMonitorThresholdPercent());
        data.put("backupScheduleHour", config.getBackupScheduleHour());
        // Vaier-server SSH-access override (#311); only written when the operator has pinned one.
        if (config.getVaierServerSshAccess() != null) {
            data.put("vaierServerSshAccess", config.getVaierServerSshAccess());
        }
        // The Vaier-server machine's identity; absent until one has been assigned.
        if (config.getVaierServerMachineId() != null) {
            data.put("vaierServerMachineId", config.getVaierServerMachineId());
        }

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(data, writer);
            log.info("Saved vaier configuration to {}", configFilePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save vaier configuration", e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    @Override
    public boolean exists() {
        return new File(configFilePath).exists();
    }

    /**
     * The stored SMTP notifier password, read back from this same owner-only config file. This is
     * Vaier's own credential store; the settings screen's "leave blank to keep current password" flow
     * and the admin-notification sender both read it here.
     */
    @Override
    public Optional<String> readStoredPassword() {
        return load().map(VaierConfig::getSmtpPassword).filter(p -> !p.isBlank());
    }
}
