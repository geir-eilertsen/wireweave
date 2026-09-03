package net.vaier.rest;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.GetMachinesUseCase;
import net.vaier.application.NotifyAdminsOfUpdateAvailableUseCase;
import net.vaier.domain.ImageUpdateRollup;
import net.vaier.domain.Machine;
import net.vaier.domain.ScopedImage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mails admins the images that just went out of date. One place, because two paths learn that news — the
 * daily {@link ImageUpdateWatcher} and the operator's own update check — and they must word it identically.
 *
 * <p>Decides nothing: {@link ImageUpdateRollup} says whether there is anything to send and how it reads.
 */
@Component
@Slf4j
public class ImageUpdateAlerter {

    private final NotifyAdminsOfUpdateAvailableUseCase notifier;

    /**
     * What to call each machine in the alert, looked up when the alert is written. The verdicts are keyed by
     * identity — a display name in that key would make a rename read as every image on the machine going
     * stale at once — so the name is fetched here, at the one moment a person is going to read it.
     */
    private final GetMachinesUseCase machines;

    public ImageUpdateAlerter(NotifyAdminsOfUpdateAvailableUseCase notifier, GetMachinesUseCase machines) {
        this.notifier = notifier;
        this.machines = machines;
    }

    /** One rollup for everything handed, or nothing at all. A dead SMTP server is logged, never thrown. */
    public void alert(List<ScopedImage> newlyOutOfDate) {
        ImageUpdateRollup rollup = new ImageUpdateRollup(newlyOutOfDate, machineNames());
        if (!rollup.worthSending()) return;
        try {
            notifier.notifyAdminsOfUpdateAvailable(rollup);
        } catch (RuntimeException e) {
            log.warn("Could not mail the update-available alert: {}", e.getMessage());
        }
    }

    /**
     * Guarded: reading the fleet can fail, and it only decorates the alert — an alert naming machines by
     * their identities still says an image went stale, where no alert at all says nothing.
     */
    private Map<String, String> machineNames() {
        try {
            return machines.getAllMachines().stream()
                .collect(Collectors.toMap(m -> m.id().value(), Machine::name, (a, b) -> a));
        } catch (RuntimeException e) {
            log.debug("Could not read machine names for the update-available alert: {}", e.getMessage());
            return Map.of();
        }
    }
}
