package net.fjordomatic.application;

import java.util.Optional;

public interface GeneratePeerSetupScriptUseCase {

    Optional<String> generateSetupScript(String peerId, String serverUrl, String serverPort);
}
