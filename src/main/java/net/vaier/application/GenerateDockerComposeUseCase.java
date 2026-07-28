package net.vaier.application;

public interface GenerateDockerComposeUseCase {

    String generateWireguardClientDockerCompose(String peerId, String serverUrl, String serverPort);
}
