package net.vaier.adapter.driven;

import net.vaier.domain.TrustedNetworks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrowdSecWhitelistFileAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    void write_rendersCrowdSecsWhitelistParserSchema() throws Exception {
        Path whitelistFile = tempDir.resolve("vaier-trusted-networks.yaml");
        CrowdSecWhitelistFileAdapter adapter = new CrowdSecWhitelistFileAdapter(whitelistFile.toString());
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16",
            List.of("192.168.1.0/24"));

        adapter.write(networks);

        String content = Files.readString(whitelistFile);
        assertThat(content).contains("name: vaier/trusted-networks");
        assertThat(content).contains("whitelist:");
        assertThat(content).contains("cidr:");
        assertThat(content).contains("- 10.13.13.0/24");
        assertThat(content).contains("- 172.20.0.0/16");
        assertThat(content).contains("- 192.168.1.0/24");
    }

    @Test
    void write_isIdempotent_rewritingProducesTheSameContent() throws Exception {
        Path whitelistFile = tempDir.resolve("vaier-trusted-networks.yaml");
        CrowdSecWhitelistFileAdapter adapter = new CrowdSecWhitelistFileAdapter(whitelistFile.toString());
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of());

        adapter.write(networks);
        String first = Files.readString(whitelistFile);
        adapter.write(networks);
        String second = Files.readString(whitelistFile);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void write_createsMissingParentDirectories() {
        Path whitelistFile = tempDir.resolve("nested/deeper/vaier-trusted-networks.yaml");
        CrowdSecWhitelistFileAdapter adapter = new CrowdSecWhitelistFileAdapter(whitelistFile.toString());
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of());

        adapter.write(networks);

        assertThat(Files.exists(whitelistFile)).isTrue();
    }
}
