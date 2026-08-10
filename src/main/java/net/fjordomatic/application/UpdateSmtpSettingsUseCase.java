package net.fjordomatic.application;

public interface UpdateSmtpSettingsUseCase {
    void updateSmtpSettings(String host, int port, String username, String password, String sender);
}
