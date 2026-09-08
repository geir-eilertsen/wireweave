package net.vaier.adapter.driven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.vaier.domain.AndroidApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app is a file in the image and nothing else. An image built from a tree that carries the package
 * serves it; one built without simply does not — and "does not" has to mean an empty answer, never an
 * exception, because the launchpad asks this question on every visit including a logged-out one.
 */
class FilesystemAndroidAppAdapterTest {

    @TempDir
    Path dir;

    @Test
    void readsThePackageThatIsThere() throws IOException {
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, new byte[]{'P', 'K', 3, 4, 7});

        Optional<AndroidApp> app = new FilesystemAndroidAppAdapter(apk.toString()).readApp();

        assertThat(app).isPresent();
        assertThat(app.get().sizeBytes()).isEqualTo(5);

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        app.get().writeTo(sink);
        assertThat(sink.toByteArray()).containsExactly('P', 'K', 3, 4, 7);
    }

    @Test
    void aMissingPackageIsAnEmptyAnswer() {
        Optional<AndroidApp> app = new FilesystemAndroidAppAdapter(dir.resolve("vaier.apk").toString()).readApp();

        assertThat(app).isEmpty();
    }

    @Test
    void aDirectoryWhereThePackageShouldBeIsAnEmptyAnswer() throws IOException {
        Path notAFile = Files.createDirectory(dir.resolve("vaier.apk"));

        assertThat(new FilesystemAndroidAppAdapter(notAFile.toString()).readApp()).isEmpty();
    }

    @Test
    void anEmptyPackageIsNoAppAtAll() throws IOException {
        // The domain's rule, reached through the adapter: a zero-byte file is a build that went wrong.
        Path apk = Files.createFile(dir.resolve("vaier.apk"));

        assertThat(new FilesystemAndroidAppAdapter(apk.toString()).readApp()).isEmpty();
    }

    @Test
    void aBlankConfiguredPathIsAnEmptyAnswer() {
        assertThat(new FilesystemAndroidAppAdapter("  ").readApp()).isEmpty();
    }

    /**
     * The default path and the Dockerfile's copy target are one fact spelled in two files. Drift between
     * them is invisible — the image builds, the app boots, and the only symptom is a launchpad that never
     * paints an Android button on a deployment that is carrying the package all along.
     */
    @Test
    void theDefaultPathIsWhereTheImageActuallyPutsThePackage() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String adapter = Files.readString(
            Path.of("src/main/java/net/vaier/adapter/driven/FilesystemAndroidAppAdapter.java"));

        assertThat(adapter).contains("${vaier.android.apk:/app/apk/vaier.apk}");
        assertThat(dockerfile).as("the runtime stage copies apk/ to /app/apk/")
            .contains("COPY --chown=1000:1000 apk/ /app/apk/");
    }
}
