package net.vaier.domain;

import java.io.OutputStream;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The <b>Vaier app</b> as Vaier knows it: the Android package this deployment can hand a phone, and
 * nothing more. Vaier never opens the package — it does not parse the APK, read its manifest or check
 * its signature — so the only facts here are the ones a file can tell, which is that there are bytes
 * and how many of them.
 *
 * <p>The decision this value exists for is <b>present or absent, never a half state</b>. A zero-byte
 * {@code vaier.apk} is what a failed build or an interrupted copy leaves behind, and offering it as a
 * download hands a phone an APK that cannot install. So an app with no bytes, or with nothing to stream
 * them from, is not an app at all — {@link #of} answers empty and every surface that asks (the
 * launchpad's Android button, the download endpoint) simply has nothing to offer.
 *
 * <p>The bytes are a {@code writer} rather than a {@code byte[]} on purpose: the package is around 20 MB
 * and it is streamed straight into the response, so nothing reads it until a phone actually asks.
 */
public record AndroidApp(long sizeBytes, Consumer<OutputStream> writer) {

    /**
     * The one name the app is ever served under. The product's name, not the build's: a phone that saved
     * {@code app-release.apk} would never recognise it as Vaier's app again.
     */
    public static final String FILE_NAME = "vaier.apk";

    /** What Android recognises as an installable package; anything else downloads as a stray file. */
    public static final String CONTENT_TYPE = "application/vnd.android.package-archive";

    public AndroidApp {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("An Android app with no bytes is not an app to serve");
        }
        if (writer == null) {
            throw new IllegalArgumentException("An Android app needs something to stream its bytes from");
        }
    }

    /**
     * The app a package of {@code sizeBytes} bytes is, or empty when there is nothing to offer — no
     * bytes, or no way to read them.
     */
    public static Optional<AndroidApp> of(long sizeBytes, Consumer<OutputStream> writer) {
        return sizeBytes > 0 && writer != null
            ? Optional.of(new AndroidApp(sizeBytes, writer))
            : Optional.empty();
    }

    /** How the download announces itself, so the browser saves it under {@link #FILE_NAME}. */
    public String contentDisposition() {
        return "attachment; filename=\"" + FILE_NAME + "\"";
    }

    /** Stream the package's bytes to {@code out} — the first and only moment they are read. */
    public void writeTo(OutputStream out) {
        writer.accept(out);
    }
}
