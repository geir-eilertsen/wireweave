package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedNetworksTest {

    @Test
    void of_rejectsBlankVpnSubnet() {
        assertThatThrownBy(() -> TrustedNetworks.of(" ", "172.20.0.0/16", List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsBlankDockerBridgeCidr() {
        assertThatThrownBy(() -> TrustedNetworks.of("10.13.13.0/24", null, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contains_trueForAnAddressInTheVpnSubnet() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of());

        assertThat(networks.contains("10.13.13.5")).isTrue();
    }

    @Test
    void contains_trueForAnAddressInTheDockerBridgeCidr() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of());

        assertThat(networks.contains("172.20.3.9")).isTrue();
    }

    @Test
    void contains_trueForAnAddressInARelayLanCidr() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16",
            List.of("192.168.1.0/24"));

        assertThat(networks.contains("192.168.1.42")).isTrue();
    }

    @Test
    void contains_falseOutsideAllThreeSources() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16",
            List.of("192.168.1.0/24"));

        assertThat(networks.contains("8.8.8.8")).isFalse();
    }

    @Test
    void allCidrs_listsVpnSubnetBridgeAndEveryRelayLanCidr() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16",
            List.of("192.168.1.0/24", "192.168.3.0/24"));

        assertThat(networks.allCidrs()).containsExactly(
            "10.13.13.0/24", "172.20.0.0/16", "192.168.1.0/24", "192.168.3.0/24");
    }

    @Test
    void allCidrs_toleratesNullRelayList() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", null);

        assertThat(networks.allCidrs()).containsExactly("10.13.13.0/24", "172.20.0.0/16");
    }

    /**
     * An address the operator permanently trusted (#329 Slice 3c) joins the allowlist as a single-host
     * CIDR. It has to: {@code CrowdSecWhitelistFileAdapter} rewrites the whitelist wholesale from
     * {@link TrustedNetworks#allCidrs()} every five minutes, so an address that is not folded in here is
     * erased from the file within five minutes of being trusted.
     */
    @Test
    void allCidrs_foldsInEveryPermanentlyTrustedAddressAsASingleHostCidr() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16",
            List.of("192.168.1.0/24"),
            List.of(SourceAddress.of("195.178.110.155"), SourceAddress.of("8.8.8.8")));

        assertThat(networks.allCidrs()).containsExactly(
            "10.13.13.0/24", "172.20.0.0/16", "192.168.1.0/24", "195.178.110.155/32", "8.8.8.8/32");
    }

    @Test
    void contains_trueForAPermanentlyTrustedAddress() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", List.of(),
            List.of(SourceAddress.of("8.8.8.8")));

        assertThat(networks.contains("8.8.8.8")).isTrue();
        assertThat(networks.contains("8.8.8.9")).isFalse();
    }

    @Test
    void allCidrs_toleratesNullTrustedAddressList() {
        TrustedNetworks networks = TrustedNetworks.of("10.13.13.0/24", "172.20.0.0/16", null, null);

        assertThat(networks.allCidrs()).containsExactly("10.13.13.0/24", "172.20.0.0/16");
    }
}
