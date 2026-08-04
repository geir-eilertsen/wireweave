package net.vaier.application;

import net.vaier.domain.SelfUpdateStatus;

/** What Vaier knows about updating itself: whether there is a newer image, and how the last attempt went. */
public interface GetSelfUpdateStatusUseCase {

    /** Whether the registry serves a newer image for the tag Vaier's own container runs. */
    boolean updateAvailable();

    /** The account the last update left on the host, or {@link SelfUpdateStatus#NONE} if there was none. */
    SelfUpdateStatus lastUpdate();
}
