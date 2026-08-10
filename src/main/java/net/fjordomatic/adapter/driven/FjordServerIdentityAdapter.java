package net.fjordomatic.adapter.driven;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.FjordConfig;
import net.fjordomatic.domain.port.ForPersistingAppConfiguration;
import net.fjordomatic.domain.port.ForResolvingFjordServerIdentity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the Fjord server's own {@link MachineId} out of the Fjord config, assigning one the first time it is
 * asked for on a Fjord that has never had it.
 *
 * <p>The read is the domain's ({@link FjordConfig#fjordServerIdentity()}, which answers empty rather than
 * substituting anything for a value it cannot use). This adapter adds only the write, and only in the one
 * case the domain reports nothing to read — which is why the whole question lives behind a single port
 * instead of being answered wherever it happens to be needed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FjordServerIdentityAdapter implements ForResolvingFjordServerIdentity {

    private final ForPersistingAppConfiguration forPersistingAppConfiguration;

    @Override
    public synchronized MachineId identity() {
        FjordConfig config = forPersistingAppConfiguration.load().orElse(null);
        if (config != null) {
            Optional<MachineId> stored = config.fjordServerIdentity();
            if (stored.isPresent()) {
                return stored.get();
            }
            if (config.getFjordServerMachineId() != null && !config.getFjordServerMachineId().isBlank()) {
                // Stored but unusable. Loud, because everything keyed to whatever the old value was meant is
                // now orphaned — and unlike every other machine, refusing to load is not an option here: the
                // Fjord server would become unreachable to its own backups, disks and self-update.
                log.error("vaierServerMachineId in the Fjord config is not a usable machine id ({});"
                    + " assigning a new one. Anything keyed to the old id will need re-pointing.",
                    config.getFjordServerMachineId());
            }
        }
        return assign(config);
    }

    /** Mint once and persist, so every later caller reads rather than assigns. */
    private MachineId assign(FjordConfig config) {
        MachineId assigned = MachineId.generate();
        FjordConfig toSave = (config == null ? FjordConfig.builder().build() : config)
            .toBuilder().fjordServerMachineId(assigned.value()).build();
        forPersistingAppConfiguration.save(toSave);
        log.info("Assigned the Fjord server its machine id {}", assigned);
        return assigned;
    }
}
