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

    /**
     * The app this deployment can serve, or empty when there is none to offer.
     *
     * @param servedHost this Vaier's own host name, stamped into the package so the app it becomes knows
     *                   where it came from and never asks a person to type an address. Null or blank
     *                   before a domain is configured — then the package is served exactly as built,
     *                   because an app that has to be told an address beats no app at all.
     */
    Optional<AndroidApp> readApp(String servedHost);
}
