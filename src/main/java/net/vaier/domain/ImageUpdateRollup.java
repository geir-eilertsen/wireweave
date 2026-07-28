package net.vaier.domain;

import java.util.List;
import java.util.Map;

/**
 * The images that became out of date in one sweep, and how that reads as an email to admins.
 *
 * <p><b>One sweep, one mail.</b> Three images going stale together is one message listing three images, not
 * three messages — the rollup exists so an alert stays something an operator reads rather than something they
 * filter. It renders itself, like {@code RemoteDiskUsage.pressureSubject} and {@code BackupRun.failureSubject}
 * do; the notification service only sequences the SMTP send.
 *
 * <p>The mail says what Vaier saw and stops. It never offers to pull: Vaier is read-only about containers, and
 * the operator's move is theirs.
 *
 * @param images       the images-on-a-machine newly found to have an update available, in the order they
 *                     should be listed
 * @param machineNames what to call each machine, by identity. Passed in rather than held on
 *                     {@link ScopedImage}, which is a map key diffed across sweeps: a display name in
 *                     that key would make a rename read as every image on the machine going stale at once.
 *                     A machine missing from this map reads as its identity, which is ugly on purpose.
 */
public record ImageUpdateRollup(List<ScopedImage> images, Map<String, String> machineNames) {

    /** A rollup with no names to hand — every machine then reads as its own identity. */
    public ImageUpdateRollup(List<ScopedImage> images) {
        this(images, Map.of());
    }

    private String nameOf(ScopedImage image) {
        return machineNames == null ? null : machineNames.get(image.machineId());
    }

    /** Whether there is anything to say. A sweep that changed nothing sends no mail at all. */
    public boolean worthSending() {
        return images != null && !images.isEmpty();
    }

    /**
     * Subject line. It names the image <b>and the machine it runs on</b> when there is one — the operator can
     * act straight from the subject, and #57's refinement is precisely that they can tell WHICH machine — and
     * counts them when there are several, since a subject listing eight image-on-a-machine labels is unreadable.
     */
    public String subject() {
        if (images.size() == 1) {
            return "[Vaier] Update available: " + images.get(0).label(nameOf(images.get(0)));
        }
        return "[Vaier] Update available: " + images.size() + " images";
    }

    /**
     * Body: every image that went stale and the machine it runs on, then the one thing the operator needs to
     * know — that nothing will happen until they act. {@code baseDomain} builds the Vaier UI link, omitted when
     * null or blank.
     */
    public String body(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append(images.size() == 1
            ? "Update available for an image running in your fleet:\n\n"
            : "Update available for images running in your fleet:\n\n");
        for (ScopedImage image : images) {
            body.append("  • ").append(image.label(nameOf(image))).append("\n");
        }
        body.append("\nThe registry now serves a different digest for the tag each container runs.\n");
        body.append("Vaier does not pull or restart anything — updating is your call.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nVaier UI: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/\n");
        }
        return body.toString();
    }
}
