package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.AndroidApp;

/**
 * Reads the <b>Vaier app</b> package this deployment serves — the Android APK shipped inside the image.
 * A driven port because the package is a file on disk (infrastructure), not something the domain holds.
 *
 * <p>An image built from a tree with no package in it is a normal, expected state, so the absence is an
 * empty answer rather than a failure: the surfaces that ask simply offer nothing.
 */
public interface ForReadingAndroidApp {

    /** The app this deployment can serve, or empty when there is none to offer. */
    Optional<AndroidApp> readApp();
}
