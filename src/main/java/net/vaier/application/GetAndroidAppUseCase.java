package net.vaier.application;

import java.util.Optional;
import net.vaier.domain.AndroidApp;

/**
 * Get the <b>Vaier app</b> this deployment serves — the Android package shipped inside the image (#359,
 * slice 1 follow-on), so a phone gets the app from the fleet it is joining rather than from a store.
 *
 * <p>Empty when the image carries no package: a build made from a tree without one is a normal state, and
 * Vaier never offers what it cannot serve.
 */
public interface GetAndroidAppUseCase {

    /** The app to serve, or empty when this deployment has none. */
    Optional<AndroidApp> androidApp();
}
