package net.vaier.adapter.driven;

import net.vaier.domain.MachineId;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Vaier server's own identity, read from its config and assigned exactly once if it has never had one.
 *
 * <p>This adapter exists because the question used to be answered in two places with different semantics —
 * one read-only, one that minted and persisted — so whether the Vaier server had an identity at all depended
 * on which caller happened to run first. That is the behaviour pinned here.
 */
class VaierServerIdentityAdapterTest {

    /** A config store that remembers what was saved, so a mint can be observed as a write. */
    static final class InMemoryConfig implements ForPersistingAppConfiguration {
        VaierConfig stored;
        final List<VaierConfig> saves = new ArrayList<>();

        InMemoryConfig(VaierConfig initial) {
            this.stored = initial;
        }

        @Override public Optional<VaierConfig> load() {
            return Optional.ofNullable(stored);
        }

        @Override public void save(VaierConfig config) {
            stored = config;
            saves.add(config);
        }

        @Override public boolean exists() {
            return stored != null;
        }
    }

    private InMemoryConfig config;

    @BeforeEach
    void setUp() {
        config = new InMemoryConfig(VaierConfig.builder().build());
    }

    private VaierServerIdentityAdapter adapter() {
        return new VaierServerIdentityAdapter(config);
    }

    @Test
    void identity_readsTheStoredId_withoutWritingAnything() {
        config.stored = VaierConfig.builder()
            .vaierServerMachineId("c0355605-e5a0-419a-8943-fdc5ec209958").build();

        assertThat(adapter().identity()).isEqualTo(MachineId.of("c0355605-e5a0-419a-8943-fdc5ec209958"));
        assertThat(config.saves).isEmpty();
    }

    @Test
    void identity_assignsAndPersistsOne_whenTheVaierServerHasNeverHadAnIdentity() {
        MachineId assigned = adapter().identity();

        assertThat(assigned).isNotNull();
        assertThat(config.saves).hasSize(1);
        assertThat(config.stored.getVaierServerMachineId()).isEqualTo(assigned.value());
    }

    /**
     * The bug this adapter was extracted to fix. The id used to be minted by one caller and only read by
     * another, so resolving the Vaier server over SSH before anything had loaded the Machines page found no
     * identity and reported the machine as missing. Every caller now asks the same question and gets the
     * same answer, whichever of them asks first.
     */
    @Test
    void identity_isTheSameAnswerForEveryCaller_whicheverAsksFirst() {
        MachineId first = adapter().identity();
        MachineId second = adapter().identity();

        assertThat(second).isEqualTo(first);
        assertThat(config.saves).hasSize(1);   // assigned once, then only read
    }

    /**
     * A hand-edited id that no longer parses is replaced rather than propagated — but this is the one place
     * in Vaier where that is allowed, and it is loud. Anywhere else a malformed id means the record does not
     * load, because coming back as a different machine orphans everything keyed to the old one in silence.
     */
    @Test
    void identity_replacesAnUnusableStoredValue_ratherThanFailingToResolveTheMachine() {
        config.stored = VaierConfig.builder().vaierServerMachineId("1-1-1-1-1").build();

        MachineId assigned = adapter().identity();

        assertThat(assigned).isNotNull();
        assertThat(config.stored.getVaierServerMachineId()).isEqualTo(assigned.value());
    }

    /** A config that has never been written at all still yields an identity rather than failing. */
    @Test
    void identity_worksOnAVaierWithNoConfigYet() {
        config.stored = null;

        assertThat(adapter().identity()).isNotNull();
        assertThat(config.saves).hasSize(1);
    }
}
