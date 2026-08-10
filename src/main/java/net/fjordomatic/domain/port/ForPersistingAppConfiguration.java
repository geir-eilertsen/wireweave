package net.fjordomatic.domain.port;

import java.util.Optional;
import net.fjordomatic.domain.FjordConfig;

public interface ForPersistingAppConfiguration {

    Optional<FjordConfig> load();

    void save(FjordConfig config);

    boolean exists();
}
