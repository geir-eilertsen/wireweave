package net.fjordomatic.domain.port;

import java.util.Optional;

public interface ForReadingStoredSmtpPassword {
    Optional<String> readStoredPassword();
}
