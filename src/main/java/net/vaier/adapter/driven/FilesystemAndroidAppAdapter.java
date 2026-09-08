package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AndroidApp;
import net.vaier.domain.port.ForReadingAndroidApp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serves the <b>Vaier app</b> straight off the image's filesystem: the Dockerfile copies {@code apk/}
 * into {@code /app/apk/}, so a build from a tree that has the package carries it and a build without one
 * carries an empty directory.
 *
 * <p>Nothing is read here but the file's size — the bytes are streamed later, when a phone actually asks,
 * so a 20 MB package never lands in Vaier's heap. Any trouble reading the file (gone, unreadable, a
 * directory in its place) is an app Vaier cannot serve, which is the same answer as having none.
 */
@Component
@Slf4j
public class FilesystemAndroidAppAdapter implements ForReadingAndroidApp {

    private final String apkPath;

    public FilesystemAndroidAppAdapter(@Value("${vaier.android.apk:/app/apk/vaier.apk}") String apkPath) {
        this.apkPath = apkPath;
    }

    @Override
    public Optional<AndroidApp> readApp() {
        if (apkPath == null || apkPath.isBlank()) {
            return Optional.empty();
        }
        Path apk = Path.of(apkPath);
        if (!Files.isRegularFile(apk)) {
            return Optional.empty();
        }
        try {
            return AndroidApp.of(Files.size(apk), out -> copy(apk, out));
        } catch (IOException e) {
            log.debug("No Android app to serve from {}: {}", apkPath, e.getMessage());
            return Optional.empty();
        }
    }

    private void copy(Path apk, OutputStream out) {
        try {
            Files.copy(apk, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
