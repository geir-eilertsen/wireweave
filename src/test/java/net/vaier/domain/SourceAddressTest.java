package net.vaier.domain;

import net.vaier.domain.port.ForLiftingBlocks;
import net.vaier.domain.port.ForPersistingTrustedAddresses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SourceAddressTest {

    @Test
    void of_acceptsAPlainIpv4Address() {
        assertThat(SourceAddress.of("195.178.110.155").value()).isEqualTo("195.178.110.155");
    }

    @Test
    void of_trimsSurroundingWhitespace() {
        assertThat(SourceAddress.of("  1.2.3.4 ").value()).isEqualTo("1.2.3.4");
    }

    // The whole point of validating in the domain: this string ends up as an argument to a command run
    // inside the crowdsec container, and in a log line. Nothing but a dotted quad may get through.
    @Test
    void of_rejectsAnythingThatIsNotAStrictIpv4Address() {
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4; rm -rf /"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4 && id"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("$(id)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("evil.example.com"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("2001:db8::1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4/24")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("01.2.3.4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("300.1.1.1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4\nX-Forged: yes"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A value shaped like a command-line flag is the specific hazard of an address that ends up as an
     * argv element of {@code cscli decisions delete -i <here>}. Even though the exec path passes an array
     * and never a shell string, an argument that starts with {@code -} is read by the <em>callee</em> as an
     * option, not as data — the one injection that survives argv separation. It never gets that far.
     */
    @Test
    void of_rejectsAValueShapedLikeACommandLineFlag() {
        assertThatThrownBy(() -> SourceAddress.of("--help")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("-i")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("--all")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("-i 1.2.3.4"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_rejectsBackticksAndOtherShellMetacharacters() {
        assertThatThrownBy(() -> SourceAddress.of("`id`")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4|id")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4 > /etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Whitespace is trimmed at the edges and never tolerated inside. The distinction matters: a stray tab
     * or newline the browser tacked on either end leaves a clean dotted quad once trimmed, so rejecting it
     * would only be rude — whereas whitespace <em>within</em> the value means it was never one address.
     */
    @Test
    void of_rejectsWhitespaceInsideTheAddress() {
        assertThatThrownBy(() -> SourceAddress.of("1.2. 3.4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3 .4")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4 5.6.7.8"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("\t\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_trimsATabOrNewlineTheSameWayItTrimsASpace() {
        assertThat(SourceAddress.of("\t1.2.3.4\n").value()).isEqualTo("1.2.3.4");
    }

    /**
     * An unbounded string from the browser must be rejected on shape, not merely truncated somewhere
     * downstream — a megabyte of digits is not an address however long a regex is willing to look at it.
     */
    @Test
    void of_rejectsAnAbsurdlyLongValue() {
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.".repeat(100_000) + "4"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SourceAddress.of("9".repeat(1_000_000)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * {@code x.x.x.x/32} is the same single host as {@code x.x.x.x}, and it is exactly what an operator
     * copies back out of the whitelist file. It is accepted and normalised to the bare address, so
     * {@link SourceAddress#value()} is always something {@code cscli decisions delete -i} can match — and
     * {@link SourceAddress#asCidr()} then renders it back unchanged.
     */
    @Test
    void of_acceptsASingleHostCidrAndNormalisesItToTheBareAddress() {
        assertThat(SourceAddress.of("195.178.110.155/32").value()).isEqualTo("195.178.110.155");
        assertThat(SourceAddress.of("195.178.110.155/32").asCidr()).isEqualTo("195.178.110.155/32");
        assertThat(SourceAddress.of("195.178.110.155/32")).isEqualTo(SourceAddress.of("195.178.110.155"));
    }

    /**
     * A range is not a source address. Vaier's two actions are per-host — {@code cscli decisions delete -i}
     * lifts one address, and the trust store is a list of hosts — so accepting a prefix would either
     * silently trust far more than the operator meant or quietly fail to unblock anything.
     */
    @Test
    void of_rejectsAWiderRangeAndSaysWhy() {
        assertThatThrownBy(() -> SourceAddress.of("192.168.1.0/24"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("single");
        assertThatThrownBy(() -> SourceAddress.of("0.0.0.0/0"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A record's canonical constructor is as public as the record, so {@code of} cannot be the only door —
     * it can only be the only <em>normalising</em> one. The gate therefore lives in the constructor as
     * well, or "no caller can route around it" is a claim the type does not actually make good on.
     */
    @Test
    void theConstructorItselfRefusesAnAddressThatDidNotComeThroughOf() {
        assertThatThrownBy(() -> new SourceAddress("$(id)")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceAddress("1.2.3.4/32"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceAddress(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The rejection message must not echo the rejected string — it is attacker-influenced text. */
    @Test
    void of_rejectionMessageDoesNotEchoTheOffendingValue() {
        assertThatThrownBy(() -> SourceAddress.of("1.2.3.4; rm -rf /"))
            .hasMessageNotContaining("rm -rf");
    }

    /**
     * A bare address joins the trusted networks as a single-host CIDR. That normalisation is the domain's
     * — an adapter or controller inventing "/32" would be a second place the rule could drift.
     */
    @Test
    void asCidr_normalisesABareAddressToASingleHostCidr() {
        assertThat(SourceAddress.of("195.178.110.155").asCidr()).isEqualTo("195.178.110.155/32");
    }

    @Test
    void liftBlock_asksThePortToLiftTheBlockOnThisAddress() {
        ForLiftingBlocks forLiftingBlocks = mock(ForLiftingBlocks.class);
        SourceAddress address = SourceAddress.of("1.2.3.4");

        address.liftBlock(forLiftingBlocks);

        verify(forLiftingBlocks).liftBlock(address);
    }

    @Test
    void trust_persistsThisAddressThroughTheStore() {
        ForPersistingTrustedAddresses store = mock(ForPersistingTrustedAddresses.class);
        SourceAddress address = SourceAddress.of("1.2.3.4");

        address.trust(store);

        verify(store).save(address);
    }
}
