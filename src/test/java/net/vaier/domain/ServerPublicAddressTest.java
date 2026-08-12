package net.vaier.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerPublicAddressTest {

    /** The live elastic IP, which DB-IP places at Frankfurt am Main. */
    private static final String OUR_EIP = "52.29.74.114";

    @Test
    void anAccessArrivingFromTheServersOwnAddressIsAHairpin() {
        assertThat(ServerPublicAddress.of(OUR_EIP).isHairpin(OUR_EIP)).isTrue();
    }

    @Test
    void anAccessFromAnyOtherAddressIsNot() {
        assertThat(ServerPublicAddress.of(OUR_EIP).isHairpin("203.0.113.7")).isFalse();
    }

    /**
     * The regression that would otherwise blank the map: before the first resolution, and on every install
     * where none succeeds, nothing may be called ours on the strength of a lookup that never happened.
     */
    @Test
    void anUnresolvedServerAddressCallsNothingAHairpin() {
        assertThat(ServerPublicAddress.unknown().value()).isNull();
        assertThat(ServerPublicAddress.unknown().isHairpin(OUR_EIP)).isFalse();
        assertThat(ServerPublicAddress.unknown().isHairpin(null)).isFalse();
    }

    @Test
    void aResolutionThatYieldedNothingIsTheSameAsUnknown() {
        assertThat(ServerPublicAddress.of(null)).isEqualTo(ServerPublicAddress.unknown());
        assertThat(ServerPublicAddress.of("   ")).isEqualTo(ServerPublicAddress.unknown());
    }

    /** IMDS, an env var and a DNS answer spell one address three ways; they are not three addresses. */
    @Test
    void theSameAddressWrittenDifferentlyIsStillOurs() {
        assertThat(ServerPublicAddress.of("  " + OUR_EIP + " ").isHairpin(OUR_EIP)).isTrue();
        assertThat(ServerPublicAddress.of("2001:DB8::1").isHairpin("2001:db8::1")).isTrue();
        assertThat(ServerPublicAddress.of(OUR_EIP).isHairpin(" " + OUR_EIP)).isTrue();
    }

    /**
     * The mirror of "unknown is not no": IMDS going quiet for one minute must not un-know an address Vaier
     * has already resolved, or the Frankfurt dot comes back for as long as the blip lasts.
     */
    @Test
    void aRefreshThatResolvedNothingKeepsTheAddressAlreadyKnown() {
        ServerPublicAddress known = ServerPublicAddress.of(OUR_EIP);

        assertThat(known.refreshedWith(null)).isEqualTo(known);
        assertThat(known.refreshedWith("   ")).isEqualTo(known);
    }

    @Test
    void aRefreshThatResolvedAnAddressTakesIt() {
        assertThat(ServerPublicAddress.unknown().refreshedWith(OUR_EIP))
            .isEqualTo(ServerPublicAddress.of(OUR_EIP));
        assertThat(ServerPublicAddress.of("198.51.100.9").refreshedWith(OUR_EIP))
            .isEqualTo(ServerPublicAddress.of(OUR_EIP));
    }

    @Test
    void anAccessWithNoAddressAtAllIsNotAHairpin() {
        assertThat(ServerPublicAddress.of(OUR_EIP).isHairpin(null)).isFalse();
        assertThat(ServerPublicAddress.of(OUR_EIP).isHairpin("  ")).isFalse();
    }
}
