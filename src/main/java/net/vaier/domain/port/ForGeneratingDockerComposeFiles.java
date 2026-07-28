package net.vaier.domain.port;

public interface ForGeneratingDockerComposeFiles {

    String generateWireguardClientDockerCompose(DockerComposeConfig config);

    record DockerComposeConfig(
        String peerId,
        String serverUrl,
        String serverPort
    ) {}
}
