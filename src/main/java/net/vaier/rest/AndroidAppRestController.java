package net.vaier.rest;

import net.vaier.application.GetAndroidAppUseCase;
import net.vaier.domain.AndroidApp;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Hands out the <b>Vaier app</b> — the Android package this deployment ships (#359). One door, one file,
 * no parameters.
 *
 * <p>Deliberately unauthenticated, on the public Traefik router: a phone fetches the app <em>before</em>
 * it can sign in and enrol, so a session gate here would be a locked door with the key behind it. Nothing
 * is disclosed by it either — the same signed package is served to every visitor, and it holds no fleet
 * secret. When the image carries no package, the answer is a plain 404; the launchpad asks with a HEAD
 * first so it never paints a button over a door that opens on nothing.
 */
@RestController
public class AndroidAppRestController {

    private final GetAndroidAppUseCase getAndroidAppUseCase;

    public AndroidAppRestController(GetAndroidAppUseCase getAndroidAppUseCase) {
        this.getAndroidAppUseCase = getAndroidAppUseCase;
    }

    /**
     * Download the app. The bytes stream straight through the response — around 20 MB never lands in
     * Vaier's heap — and Spring answers a {@code HEAD} on this same mapping, which is the question the
     * launchpad asks before offering the button.
     */
    @GetMapping("/app/android/vaier.apk")
    public ResponseEntity<StreamingResponseBody> download() {
        return getAndroidAppUseCase.androidApp()
            .map(app -> ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, app.contentDisposition())
                .contentType(MediaType.valueOf(AndroidApp.CONTENT_TYPE))
                .contentLength(app.sizeBytes())
                .body((StreamingResponseBody) app::writeTo))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
