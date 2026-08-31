package net.vaier.config;

import net.vaier.domain.ImageUpdateTracker;
import net.vaier.domain.port.ForPersistingImageUpdateState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the update-available alert state one thing (#57 slice 3).
 *
 * <p>{@link ImageUpdateTracker} holds which images are currently <em>known</em> to be out of date, and two
 * collaborators now touch it: the daily watcher, which reports edge transitions and mails admins, and the
 * container service, whose operator-driven check clears an image's state once a pull is confirmed. They must
 * be looking at the same memory. Two instances would each hold half the truth — a check would clear a state
 * the mailer never had, and the mailer would go on believing an image was stale months after the operator
 * fixed it, silently declining to re-alert when it genuinely went stale again.
 *
 * <p>A {@code @Bean} rather than a {@code @Component} because the tracker is a domain object and the domain
 * carries no Spring annotations. Wiring is infrastructure's job, so the wiring lives here.
 *
 * <p><b>And one memory is not enough — it has to outlive the process.</b> This bean used to be built with a
 * bare {@code new ImageUpdateTracker()}, so the latch was wiped by every restart and the first sweep after
 * one re-announced every image that was still out of date. Handing the tracker a
 * {@link ForPersistingImageUpdateState} is what makes "already told them" a statement about the fleet rather
 * than about the current JVM. Same shape as {@code RemoteDiskWatcher} handing the disk tracker its port.
 */
@Configuration
public class ImageUpdateConfig {

    @Bean
    public ImageUpdateTracker imageUpdateTracker(ForPersistingImageUpdateState imageUpdateState) {
        return new ImageUpdateTracker(imageUpdateState);
    }
}
