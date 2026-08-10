package net.fjordomatic.application;

import java.util.Optional;

public interface GenerateBackupServerSetupScriptUseCase {

    /**
     * Renders the bootstrap {@code setup.sh} a host runs to stand up the named {@link
     * net.fjordomatic.domain.BackupServer} from nothing — an idempotent, pinned borg-server compose brought up
     * with {@code docker compose up -d}. Empty when no server with that name is configured (mirroring
     * {@link GenerateLanServerSetupScriptUseCase}).
     */
    Optional<String> generateSetupScript(String serverName);
}
