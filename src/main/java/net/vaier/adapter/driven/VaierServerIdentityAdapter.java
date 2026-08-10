package net.vaier.adapter.driven;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.MachineId;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForResolvingVaierServerIdentity;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Reads the Vaier server's own {@link MachineId} out of the Vaier config, assigning one the first time it is
 * asked for on a Vaier that has never had it.
 *
 * <p>The read is the domain's ({@link VaierConfig#vaierServerIdentity()}, which answers empty rather than
 * substituting anything for a value it cannot use). This adapter adds only the write, and only in the one
 * case the domain reports nothing to read — which is why the whole question lives behind a single port
 * instead of being answered wherever it happens to be needed.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class VaierServerIdentityAdapter implements ForResolvingVaierServerIdentity {

    private final ForPersistingAppConfiguration forPersistingAppConfiguration;

    @Override
    public synchronized MachineId identity() {
        VaierConfig config = forPersistingAppConfiguration.load().orElse(null);
        if (config != null) {
            Optional<MachineId> stored = config.vaierServerIdentity();
            if (stored.isPresent()) {
                return stored.get();
            }
            if (config.getVaierServerMachineId() != null && !config.getVaierServerMachineId().isBlank()) {
                // Stored but unusable. Loud, because everything keyed to whatever the old value was meant is
                // now orphaned — and unlike every other machine, refusing to load is not an option here: the
                // Vaier server would become unreachable to its own backups, disks and self-update.
                log.error("vaierServerMachineId in the Vaier config is not a usable machine id ({});"
                    + " assigning a new one. Anything keyed to the old id will need re-pointing.",
                    config.getVaierServerMachineId());
            }
        }
        return assign(config);
    }

    /** Mint once and persist, so every later caller reads rather than assigns. */
    private MachineId assign(VaierConfig config) {
        MachineId assigned = MachineId.generate();
        VaierConfig toSave = (config == null ? VaierConfig.builder().build() : config)
            .toBuilder().vaierServerMachineId(assigned.value()).build();
        forPersistingAppConfiguration.save(toSave);
        log.info("Assigned the Vaier server its machine id {}", assigned);
        return assigned;
    }
}
