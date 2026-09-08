package net.vaier.domain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Vaier app as Vaier knows it. Vaier never opens the package — it serves it — so the only facts it
 * has are the ones a file can tell: that there are bytes, and how many. The one decision that matters is
 * therefore whether there is an app to offer at all, and it has to be all-or-nothing: a half-copied or
 * truncated file offered as a download is a phone left with an APK that will not install.
 */
class AndroidAppTest {

    private static final Consumer<OutputStream> WRITER = out -> {
    };

    @Test
    void anAppWithBytes_isPresent() {
        Optional<AndroidApp> app = AndroidApp.of(20_467_986L, WRITER);

        assertThat(app).isPresent();
        assertThat(app.get().sizeBytes()).isEqualTo(20_467_986L);
    }

    @Test
    void anEmptyPackage_isNoAppAtAll() {
        // A zero-byte vaier.apk is what a failed build or an interrupted copy leaves behind. Present or
        // absent, never a half state — so it reads as absent and the button that offers it is never painted.
        assertThat(AndroidApp.of(0L, WRITER)).isEmpty();
        assertThat(AndroidApp.of(-1L, WRITER)).isEmpty();
    }

    @Test
    void anAppWithNothingToStream_isNoAppAtAll() {
        assertThat(AndroidApp.of(20_467_986L, null)).isEmpty();
    }

    @Test
    void theAppIsAlwaysServedUnderTheOneName() {
        // The name is the product's, not the build's: the app is linked to from the launchpad and the
        // Explorer, and a phone that saved "app-release.apk" would never be recognised again.
        assertThat(AndroidApp.FILE_NAME).isEqualTo("vaier.apk");
        assertThat(AndroidApp.CONTENT_TYPE).isEqualTo("application/vnd.android.package-archive");

        AndroidApp app = AndroidApp.of(10L, WRITER).orElseThrow();
        assertThat(app.contentDisposition()).isEqualTo("attachment; filename=\"vaier.apk\"");
    }

    @Test
    void theBytesAreStreamedOnlyWhenAskedFor() {
        // The package is 20 MB. Nothing reads it until a phone actually asks, and then it goes straight
        // out of the response rather than through Vaier's heap.
        boolean[] read = {false};
        AndroidApp app = AndroidApp.of(4L, out -> {
            read[0] = true;
            try {
                out.write(new byte[]{1, 2, 3, 4});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).orElseThrow();

        assertThat(read[0]).isFalse();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        app.writeTo(sink);

        assertThat(read[0]).isTrue();
        assertThat(sink.toByteArray()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void anAppCannotBeBuiltAroundNothing() {
        assertThatThrownBy(() -> new AndroidApp(0L, WRITER)).isInstanceOf(IllegalArgumentException.class);
    }
}
