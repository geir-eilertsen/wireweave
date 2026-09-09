package net.vaier.adapter.driven;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AndroidApp;
import net.vaier.domain.ApkStamp;
import net.vaier.domain.port.ForReadingAndroidApp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serves the <b>Vaier app</b> straight off the image's filesystem: the Dockerfile copies {@code apk/}
 * into {@code /app/apk/}, so a build from a tree that has the package carries it and a build without one
 * carries an empty directory.
 *
 * <p>What is handed out is the package with this deployment's host name stamped into its signing block —
 * the domain cuts the stamp, this only decides where the result lives. It lives <b>on disk beside the
 * source</b>, as {@code vaier.apk.stamped}: the host is fixed for the life of the deployment, so the
 * stamp is cut once, and the package is around 20 MB, which is a second copy Vaier is not willing to
 * pin in the heap for the life of the process just to avoid writing a file. Downloads still stream.
 * A source whose size or timestamp has moved — an upgrade dropped a new package in — is stamped afresh.
 *
 * <p>Nothing else is read here but the file's size, and every way this can go wrong ends in the app being
 * served rather than withheld: a package with no signing block, or a directory that cannot be written to,
 * is served exactly as it was built, with one line in the log saying so. Trouble reading the source at
 * all is an app Vaier cannot serve, which is the same answer as having none.
 */
@Component
@Slf4j
public class FilesystemAndroidAppAdapter implements ForReadingAndroidApp {

    private static final String STAMPED_SUFFIX = ".stamped";

    private final String apkPath;

    /** What the kept copy was cut from, so a rebuilt or re-stamped package is noticed. Guarded by this. */
    private StampedPackage kept;

    public FilesystemAndroidAppAdapter(@Value("${vaier.android.apk:/app/apk/vaier.apk}") String apkPath) {
        this.apkPath = apkPath;
    }

    @Override
    public Optional<AndroidApp> readApp(String servedHost) {
        if (apkPath == null || apkPath.isBlank()) {
            return Optional.empty();
        }
        Path source = Path.of(apkPath);
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        try {
            Path toServe = servedHost == null || servedHost.isBlank()
                ? source
                : stampedCopyOf(source, servedHost);
            return AndroidApp.of(Files.size(toServe), out -> copy(toServe, out));
        } catch (IOException e) {
            log.debug("No Android app to serve from {}: {}", apkPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The stamped package to serve — cut now if this source and host have not been stamped yet, and
     * otherwise the copy already sitting beside the source. The source itself comes back when the stamp
     * could not be cut, so an unstampable package is not re-read on every download either.
     */
    private synchronized Path stampedCopyOf(Path source, String servedHost) throws IOException {
        StampedPackage current = new StampedPackage(Files.size(source),
            Files.getLastModifiedTime(source).toMillis(), servedHost, null);
        if (kept != null && kept.cutFrom(current) && Files.isRegularFile(kept.served())) {
            return kept.served();
        }
        Path served = stamp(source, servedHost);
        kept = current.servedFrom(served);
        return served;
    }

    private Path stamp(Path source, String servedHost) throws IOException {
        Optional<byte[]> stamped = ApkStamp.stampedWith(Files.readAllBytes(source), servedHost);
        if (stamped.isEmpty()) {
            log.warn("The Vaier app at {} has no APK Signing Block, so it cannot carry {}; serving it as "
                + "built — the app will have to be told this Vaier's address by hand", source, servedHost);
            return source;
        }
        Path target = source.resolveSibling(source.getFileName() + STAMPED_SUFFIX);
        try {
            Path scratch = Files.createTempFile(target.toAbsolutePath().getParent(), ".vaier-apk", ".tmp");
            Files.write(scratch, stamped.get());
            Files.move(scratch, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Vaier app stamped with {} and kept at {}", servedHost, target);
            return target;
        } catch (IOException e) {
            log.warn("Cannot keep a stamped Vaier app beside {} ({}); serving it as built", source, e.getMessage());
            return source;
        }
    }

    private void copy(Path apk, OutputStream out) {
        try {
            Files.copy(apk, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The source a kept stamp was cut from, and where the result went. */
    private record StampedPackage(long sourceSize, long sourceModified, String servedHost, Path served) {

        boolean cutFrom(StampedPackage source) {
            return sourceSize == source.sourceSize()
                && sourceModified == source.sourceModified()
                && servedHost.equals(source.servedHost());
        }

        StampedPackage servedFrom(Path path) {
            return new StampedPackage(sourceSize, sourceModified, servedHost, path);
        }
    }
}
