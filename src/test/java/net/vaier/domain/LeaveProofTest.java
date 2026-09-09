package net.vaier.domain;

import net.vaier.domain.port.ForGettingPeerConfigurations.PeerConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole authorisation for a phone leaving the fleet on its own: it presents its public key and
 * the preshared key it was handed at approval, and only the pair together — against a peer that
 * actually holds a device-held key — proves it is the phone it says it is.
 */
class LeaveProofTest {

    private static final String DEVICE_KEY = "xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=";
    private static final String OTHER_KEY = "aFPKMlUyDXQpBQwGA2CqcnMkbQ7yYbCKAsmLcVcLzGA=";
    private static final String PSK = "cGKrDp0z0Fs0IiUrPzuTfnJ7CEZzSXpGX0ZlLBFgLGE=";
    private static final String OTHER_PSK = "TmV2ZXJUaGVTYW1lUHJlc2hhcmVkS2V5Rm9yVHdvUGU=";

    private static PeerConfiguration peer(String id, String publicKey, String presharedKey) {
        String config = """
            # VAIER: {"name":"%s"}
            [Interface]
            Address = 10.13.13.7/32

            [Peer]
            PublicKey = SERVER_PUB
            PresharedKey = %s
            Endpoint = vpn.example.com:51820
            """.formatted(id, presharedKey);
        return new PeerConfiguration(id, id, "10.13.13.7", config, MachineType.MOBILE_CLIENT,
            null, null, null, null, null, MachineId.generate(), publicKey);
    }

    @Test
    void of_refusesAnEmptyClaim() {
        assertThatThrownBy(() -> LeaveProof.of(null, PSK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaveProof.of(DEVICE_KEY, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaveProof.of("  ", PSK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LeaveProof.of(DEVICE_KEY, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proves_thePeerWhoseDeviceKeyAndPresharedKeyBothMatch() {
        assertThat(LeaveProof.of(DEVICE_KEY, PSK).proves(peer("ruten", DEVICE_KEY, PSK))).isTrue();
    }

    @Test
    void proves_nothingWhenThePresharedKeyIsWrong() {
        // The public key is public — it is on the server and in every listing. Only the preshared key,
        // which lives on the phone and in Vaier's copy of the config, can prove anything.
        assertThat(LeaveProof.of(DEVICE_KEY, OTHER_PSK).proves(peer("ruten", DEVICE_KEY, PSK))).isFalse();
    }

    @Test
    void proves_nothingWhenTheDeviceKeyIsAnotherPeers() {
        assertThat(LeaveProof.of(OTHER_KEY, PSK).proves(peer("ruten", DEVICE_KEY, PSK))).isFalse();
    }

    @Test
    void proves_nothingAgainstAPeerThatDidNotMakeItsOwnKey() {
        // A QR peer's config was minted by Vaier and handed over as a file; anyone who has ever held
        // that file could present its preshared key. Leaving is offered only to a device-held key.
        PeerConfiguration qrPeer = peer("nuc02", null, PSK);

        assertThat(qrPeer.deviceHeldKey()).isFalse();
        assertThat(LeaveProof.of(DEVICE_KEY, PSK).proves(qrPeer)).isFalse();
    }

    @Test
    void proves_nothingWhenTheStoredConfigCarriesNoPresharedKeyAtAll() {
        PeerConfiguration damaged = new PeerConfiguration("ruten", "Ruten", "10.13.13.7",
            "[Interface]\nAddress = 10.13.13.7/32\n", MachineType.MOBILE_CLIENT, null, null, null,
            null, null, MachineId.generate(), DEVICE_KEY);

        assertThat(LeaveProof.of(DEVICE_KEY, PSK).proves(damaged)).isFalse();
    }

    @Test
    void proves_nothingAgainstNothing() {
        assertThat(LeaveProof.of(DEVICE_KEY, PSK).proves(null)).isFalse();
    }

    @Test
    void whichPeer_picksTheOneItProves() {
        List<PeerConfiguration> fleet = List.of(
            peer("nuc02", null, PSK),
            peer("kikkut", OTHER_KEY, OTHER_PSK),
            peer("ruten", DEVICE_KEY, PSK));

        assertThat(LeaveProof.of(DEVICE_KEY, PSK).whichPeer(fleet))
            .map(PeerConfiguration::id).contains("ruten");
    }

    @Test
    void whichPeer_isEmptyWhenNothingInTheFleetIsProved() {
        List<PeerConfiguration> fleet = List.of(peer("kikkut", OTHER_KEY, OTHER_PSK));

        assertThat(LeaveProof.of(DEVICE_KEY, PSK).whichPeer(fleet)).isEmpty();
        assertThat(LeaveProof.of(DEVICE_KEY, PSK).whichPeer(List.of())).isEmpty();
        assertThat(LeaveProof.of(DEVICE_KEY, PSK).whichPeer(null)).isEmpty();
    }
}
