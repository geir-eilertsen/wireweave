package net.fjordomatic.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A machine's own answer to "which IPv4 networks are you on, and which one carries your default route?",
 * parsed from the {@link MachineNetworks#IP_COMMAND} run over the very SSH channel the disk sweep already
 * uses. The parser is <b>total</b> in the same sense {@code RemoteDiskUsage.parseList} is: whatever the far
 * side said, it either yields a reading or yields nothing — it never throws and never guesses.
 */
class MachineNetworksTest {

    // What `ip -o -4 addr show; ip -o -4 route show default` really prints on a home relay: loopback, the
    // LAN interface, Docker's bridge, a per-container veth, the WireGuard tunnel, then the default route.
    private static final String RELAY_OUTPUT = """
        1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever
        2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0\\       valid_lft forever preferred_lft forever
        3: docker0    inet 172.17.0.1/16 brd 172.17.255.255 scope global docker0\\       valid_lft forever preferred_lft forever
        4: br-9f2c1a    inet 172.18.0.1/16 brd 172.18.255.255 scope global br-9f2c1a\\       valid_lft forever
        5: veth3a1b@if4    inet 169.254.1.1/32 scope global veth3a1b\\       valid_lft forever
        6: wg0    inet 10.13.13.3/32 scope global wg0\\       valid_lft forever preferred_lft forever
        default via 192.168.1.1 dev eth0 proto dhcp src 192.168.1.10 metric 100
        """;

    @Test
    void parse_keepsOnlyTheInterfacesThatCouldBeAnOperatorsLan() {
        MachineNetworks networks = MachineNetworks.parse(RELAY_OUTPUT);

        assertThat(networks.networks()).extracting(MachineNetworks.Network::interfaceName)
            .containsExactly("eth0");
    }

    @Test
    void parse_readsTheAddressAndTheNetworkItSitsOn() {
        MachineNetworks networks = MachineNetworks.parse(RELAY_OUTPUT);

        MachineNetworks.Network lan = networks.networks().get(0);
        assertThat(lan.address()).isEqualTo("192.168.1.10");
        assertThat(lan.prefixLength()).isEqualTo(24);
        assertThat(lan.cidr()).isEqualTo("192.168.1.0/24");
    }

    @Test
    void parse_readsWhichInterfaceCarriesTheDefaultRoute() {
        MachineNetworks networks = MachineNetworks.parse(RELAY_OUTPUT);

        assertThat(networks.defaultRouteInterface()).isEqualTo("eth0");
        assertThat(networks.uplinkAddress()).contains("192.168.1.10");
    }

    @Test
    void lanCandidate_isTheNetworkTheMachineReachesTheWorldThrough() {
        MachineNetworks networks = MachineNetworks.parse(RELAY_OUTPUT);

        assertThat(networks.lanCandidate()).hasValueSatisfying(n -> {
            assertThat(n.cidr()).isEqualTo("192.168.1.0/24");
            assertThat(n.interfaceName()).isEqualTo("eth0");
        });
    }

    @Test
    void lanCandidate_whenTheDefaultRouteRunsOverTheTunnel_isNothing() {
        // A full-tunnel peer's default route leaves via wg0, which is never an operator's LAN. There is
        // nothing here Fjord could honestly call "the network behind it", so it says nothing.
        MachineNetworks networks = MachineNetworks.parse("""
            1: lo    inet 127.0.0.1/8 scope host lo
            6: wg0    inet 10.13.13.7/32 scope global wg0
            default dev wg0 scope link
            """);

        assertThat(networks.lanCandidate()).isEmpty();
        assertThat(networks.uplinkAddress()).isEmpty();
    }

    @Test
    void lanCandidate_withNoDefaultRouteAtAll_isNothing() {
        MachineNetworks networks = MachineNetworks.parse(
            "2: eth0    inet 192.168.1.10/24 brd 192.168.1.255 scope global eth0");

        assertThat(networks.networks()).hasSize(1);
        assertThat(networks.lanCandidate()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lo", "wg0", "wg-home", "docker0", "br-9f2c1a", "veth3a1b", "tailscale0",
        "virbr0", "tun0", "tap0", "zt0", "cni0"})
    void isPseudoInterface_neverAnOperatorsLan(String name) {
        assertThat(MachineNetworks.isPseudoInterface(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eth0", "eno1", "enp3s0", "wlan0", "ens5", "bond0", "br0"})
    void isPseudoInterface_aRealNicIsNot(String name) {
        assertThat(MachineNetworks.isPseudoInterface(name)).isFalse();
    }

    @Test
    void parse_totalLikeTheDiskParser_neverThrowsOnRubbish() {
        // A host with no `ip` at all, a truncated run, an unreadable row — every one of them is "Fjord
        // cannot tell", never a guess. Rows it cannot read are skipped; their siblings are kept.
        assertThat(MachineNetworks.parse(null).networks()).isEmpty();
        assertThat(MachineNetworks.parse("").networks()).isEmpty();
        assertThat(MachineNetworks.parse("sh: ip: command not found").networks()).isEmpty();
        assertThat(MachineNetworks.parse("""
            2: eth0    inet not-an-address/24 scope global eth0
            3: eth1    inet 10.5.0.4/24 scope global eth1
            """).networks()).extracting(MachineNetworks.Network::interfaceName).containsExactly("eth1");
    }

    @Test
    void parse_anImpossiblePrefixIsSkippedRatherThanGuessedAt() {
        assertThat(MachineNetworks.parse("2: eth0    inet 192.168.1.10/33 scope global eth0").networks())
            .isEmpty();
    }

    @Test
    void unknown_isWhatFjordHasBeforeItHasEverReadAMachine() {
        assertThat(MachineNetworks.unknown().networks()).isEmpty();
        assertThat(MachineNetworks.unknown().lanCandidate()).isEmpty();
        assertThat(MachineNetworks.unknown().uplinkAddress()).isEmpty();
    }

    @Test
    void isUnknown_isTheReadingsOwnAnswerToWhetherItSaysAnything() {
        // "This told Fjord nothing" is a reading of the reading, so the reading answers it. A caller that
        // spelled it `networks().isEmpty()` would be restating what unknown() means somewhere unknown()
        // cannot see — and would drift the first time a reading gains another way of being empty.
        assertThat(MachineNetworks.unknown().isUnknown()).isTrue();
        assertThat(MachineNetworks.parse("sh: ip: command not found").isUnknown()).isTrue();
        // A default route naming an interface Fjord holds no usable address for says nothing either.
        assertThat(MachineNetworks.parse("default dev wg0 scope link").isUnknown()).isTrue();
        assertThat(MachineNetworks.parse(RELAY_OUTPUT).isUnknown()).isFalse();
    }

    @Test
    void ipCommand_asksForBothHalvesInOneRun() {
        // One exec, two questions: the addresses and which interface the default route leaves by. Asking
        // them separately would double the SSH connects the sweep makes.
        assertThat(MachineNetworks.IP_COMMAND).contains("-o -4 addr").contains("route show default");
    }
}
