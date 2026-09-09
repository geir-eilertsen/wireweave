package net.vaier.adapter.driven;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.vaier.domain.AndroidApp;
import net.vaier.domain.ApkStamp;
import net.vaier.testsupport.SyntheticApk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app is a file in the image and nothing else. An image built from a tree that carries the package
 * serves it; one built without simply does not — and "does not" has to mean an empty answer, never an
 * exception, because the launchpad asks this question on every visit including a logged-out one.
 *
 * <p>What is served is the package <em>stamped with this Vaier's own host name</em>, so the app arrives
 * already knowing where it came from. The stamp is cut once per deployment and kept on disk beside the
 * source: the package is around 20 MB, and holding a second copy of it in the heap for the life of the
 * process to save a file is not a trade Vaier makes.
 */
class FilesystemAndroidAppAdapterTest {

    private static final String HOST = "vaier.example.com";

    @TempDir
    Path dir;

    private byte[] served(Optional<AndroidApp> app) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        app.orElseThrow().writeTo(sink);
        assertThat(app.get().sizeBytes()).as("the Content-Length matches the bytes that follow")
            .isEqualTo(sink.size());
        return sink.toByteArray();
    }

    @Test
    void readsThePackageThatIsThere() throws IOException {
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, new byte[]{'P', 'K', 3, 4, 7});

        Optional<AndroidApp> app = new FilesystemAndroidAppAdapter(apk.toString()).readApp(HOST);

        assertThat(app).isPresent();
        assertThat(served(app)).containsExactly('P', 'K', 3, 4, 7);
    }

    @Test
    void aMissingPackageIsAnEmptyAnswer() {
        Path apk = dir.resolve("vaier.apk");

        assertThat(new FilesystemAndroidAppAdapter(apk.toString()).readApp(HOST)).isEmpty();
    }

    @Test
    void aDirectoryWhereThePackageShouldBeIsAnEmptyAnswer() throws IOException {
        Path notAFile = Files.createDirectory(dir.resolve("vaier.apk"));

        assertThat(new FilesystemAndroidAppAdapter(notAFile.toString()).readApp(HOST)).isEmpty();
    }

    @Test
    void anEmptyPackageIsNoAppAtAll() throws IOException {
        // The domain's rule, reached through the adapter: a zero-byte file is a build that went wrong.
        Path apk = Files.createFile(dir.resolve("vaier.apk"));

        assertThat(new FilesystemAndroidAppAdapter(apk.toString()).readApp(HOST)).isEmpty();
    }

    @Test
    void aBlankConfiguredPathIsAnEmptyAnswer() {
        assertThat(new FilesystemAndroidAppAdapter("  ").readApp(HOST)).isEmpty();
    }

    @Test
    void thePackageIsServedStampedWithTheHostThatServedIt() throws IOException {
        // The whole point of the stamp: the phone that installs this never has to be told an address.
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, SyntheticApk.signed());

        byte[] out = served(new FilesystemAndroidAppAdapter(apk.toString()).readApp(HOST));

        assertThat(ApkStamp.hostIn(out)).contains(HOST);
        assertThat(Files.readAllBytes(apk)).as("the source is left exactly as the build made it")
            .isEqualTo(SyntheticApk.signed());
    }

    @Test
    void theStampIsCutOnceAndKeptBesideTheSource() throws IOException {
        // The host is fixed for the life of the deployment, so re-stamping 20 MB on every download would
        // be work done for nothing. Proved by scribbling over the kept copy: a second read that still
        // hands back the scribble never went near the source.
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, SyntheticApk.signed());
        FilesystemAndroidAppAdapter adapter = new FilesystemAndroidAppAdapter(apk.toString());
        served(adapter.readApp(HOST));

        Path kept = dir.resolve("vaier.apk.stamped");
        assertThat(kept).exists();
        Files.write(kept, "kept".getBytes(StandardCharsets.UTF_8));

        assertThat(served(adapter.readApp(HOST))).isEqualTo("kept".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRebuiltPackageIsStampedAgain() throws IOException {
        // An upgrade drops a new vaier.apk in place. Serving the previous deployment's stamped copy would
        // hand every phone the old app forever, so a source whose size or timestamp moved is stamped afresh.
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, SyntheticApk.signed());
        FilesystemAndroidAppAdapter adapter = new FilesystemAndroidAppAdapter(apk.toString());
        served(adapter.readApp(HOST));
        Files.write(dir.resolve("vaier.apk.stamped"), "stale".getBytes(StandardCharsets.UTF_8));

        byte[] rebuilt = SyntheticApk.signedWithoutVerityPadding();
        Files.write(apk, rebuilt);

        byte[] out = served(adapter.readApp(HOST));
        assertThat(out).isNotEqualTo("stale".getBytes(StandardCharsets.UTF_8));
        assertThat(ApkStamp.hostIn(out)).contains(HOST);
        assertThat(out.length).isEqualTo(rebuilt.length + 12 + HOST.length());
    }

    @Test
    void aPackageWithNoSigningBlockIsServedUnstamped() throws IOException {
        // A v1-only APK has nowhere to put the stamp. Vaier serves it anyway — an app the phone has to be
        // told an address for beats no app at all — and says so once in the log.
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, SyntheticApk.v1Only());

        byte[] out = served(new FilesystemAndroidAppAdapter(apk.toString()).readApp(HOST));

        assertThat(out).isEqualTo(SyntheticApk.v1Only());
        assertThat(dir.resolve("vaier.apk.stamped")).doesNotExist();
    }

    @Test
    void withNoHostYetTheAppIsStillServed() throws IOException {
        // Before a domain is configured there is no host to stamp, and the download must not wait for one.
        Path apk = dir.resolve("vaier.apk");
        Files.write(apk, SyntheticApk.signed());

        assertThat(served(new FilesystemAndroidAppAdapter(apk.toString()).readApp(null)))
            .isEqualTo(SyntheticApk.signed());
        assertThat(served(new FilesystemAndroidAppAdapter(apk.toString()).readApp("  ")))
            .isEqualTo(SyntheticApk.signed());
    }

    /**
     * The default path and the Dockerfile's copy target are one fact spelled in two files. Drift between
     * them is invisible — the image builds, the app boots, and the only symptom is a launchpad that never
     * paints an install card on a deployment that is carrying the package all along.
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
