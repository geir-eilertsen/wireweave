package net.vaier.domain;

import net.vaier.domain.port.ForPersistingImageUpdateState;
import net.vaier.domain.port.ForStoringContainerSnapshots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-image update-available state, so admins are mailed only when an image <b>becomes</b> out of date —
 * not every sweep, for as long as it stays out of date. The daily sweep and the operator's own update check
 * both fold their verdicts in through {@link #update}, so whichever of them learns the news mails it, once. The sibling of {@link RemoteDiskPressureTracker} and
 * {@link PeerConnectivityTracker}: it reports edge transitions and nothing else, and the watcher decides only
 * whom to tell. Its own state it reaches through {@link ForPersistingImageUpdateState}; the wiring hands the
 * port in and the decisions all stay here.
 *
 * <p>Three rules differ from its siblings, and all three are deliberate:
 *
 * <p><b>It is not baseline-quiet.</b> The disk and peer trackers swallow their first observation so a Vaier
 * restart raises no noise, which is right for a level that is true only at this instant. Staleness is not that
 * — it persists, and it is what #57 is about: a vaultwarden image that was <em>already</em> stale, quietly
 * breaking mobile sync. If the first sweep of an image Vaier has never judged were silent, the very case this
 * feature exists for would be the case it never reported. So a first sighting of an out-of-date image is news.
 *
 * <p><b>But a restart is not a first sighting — and here this class takes the OPPOSITE decision to the disk
 * tracker, on purpose.</b> Read that one first and this looks like a bug, so: the disk fix (commit
 * {@code 26196ef}) persisted its latch <em>and</em> made the first observation after a restart speak, because
 * a filesystem that was already full when Vaier started deserves to be heard right now. An out-of-date image
 * does not, and the difference is that the operator has <em>already been told</em>. Nothing about it changed
 * while Vaier was down; repeating it is precisely the noise being removed — the same two or three images
 * mailed several times a day, because this project rebuilds and redeploys on every change. So the latch is
 * persisted and the restart stays <b>silent</b>: same mechanism, inverse conclusion. Do not "fix" this into
 * speaking on boot; that is the bug, not the behaviour.
 *
 * <p><b>{@link UpdateAvailability#UNKNOWN} is not a change.</b> An unreachable or rate-limited registry must
 * not be read as the operator having pulled the image: if it were, the next successful sweep would re-mail
 * them about an image they were told about already, and the flapping would teach them to filter the alert.
 * Unknown therefore leaves the last known verdict standing, untouched.
 */
public class ImageUpdateTracker {

    /**
     * The images-on-a-machine last <em>known</em> to be out of date. Held by {@link ScopedImage}, not by image
     * string, so the same tag going stale on a second machine is a fresh edge rather than a state the first
     * machine's verdict already claimed. Membership is the whole latch: an image absent from it — up to date,
     * or never judged — is one an edge may still fire for. Unknown verdicts never write here.
     */
    private final ForPersistingImageUpdateState knownOutOfDate;

    /**
     * Where the <b>moving tags</b> this tracker works out are written, so the containers the Explorer reads
     * carry the same fact. The tracker is the one place that holds the digest history, so it is the one
     * place that can say which tags are channels — and a domain operation that needs infrastructure is
     * handed the driven port and calls it.
     */
    private final ForStoringContainerSnapshots snapshots;

    public ImageUpdateTracker(ForPersistingImageUpdateState knownOutOfDate,
                              ForStoringContainerSnapshots snapshots) {
        this.knownOutOfDate = knownOutOfDate;
        this.snapshots = snapshots;
    }

    /**
     * Record a sweep's verdicts and report the images-on-a-machine that have <b>just</b> become out of date,
     * ordered by their rendered label so a rollup email reads the same way twice.
     *
     * <p>Entries absent from {@code sweep} are forgotten: the container is gone, and if that image ever
     * comes back stale on that machine it is news again rather than a silence.
     *
     * <p><b>A moving tag earns the mark and never the mail.</b> {@code netdata/netdata:latest} is Docker
     * Hub's {@code :edge} — a nightly — so every morning's sweep truthfully finds it out of date and the
     * operator was mailed about it every morning. The verdict is right; the alert is not, because a tag that
     * moves every night is a channel rather than trouble. So the sweep's registry answers advance a
     * {@link RegistryDigestHistory}, and an image whose tag has shown that rhythm is latched without being
     * reported.
     */
    public synchronized List<ScopedImage> update(ImageUpdateSweep.Result sweep) {
        Set<ScopedImage> known = knownOutOfDate.loadOutOfDate();
        Set<ScopedImage> stillOutOfDate = new LinkedHashSet<>();
        List<ScopedImage> newlyOutOfDate = new ArrayList<>();

        RegistryDigestHistory history = knownOutOfDate.loadRegistryDigestHistory()
            .after(sweep.sweptImages(), sweep.registryDigests(), sweep.sweptAt());
        knownOutOfDate.saveRegistryDigestHistory(history);
        snapshots.storeMovingTags(history.movingImages(sweep.sweptAt()));

        for (Map.Entry<ScopedImage, UpdateAvailability> entry : sweep.verdicts().entrySet()) {
            ScopedImage scoped = entry.getKey();
            UpdateAvailability verdict = entry.getValue();
            if (verdict == null || verdict == UpdateAvailability.UNKNOWN) {
                // Cannot tell — leave what was known standing, and say nothing.
                if (known.contains(scoped)) stillOutOfDate.add(scoped);
                continue;
            }
            if (verdict.isUpdateAvailable()) {
                // Latched either way — including for a moving tag. Recording the change is what stops the
                // silence ending in a backlog: when the tag finally settles, the operator is told about its
                // NEXT change rather than about one from days ago that nothing was ever going to be done to.
                stillOutOfDate.add(scoped);
                if (!known.contains(scoped) && !history.isMoving(scoped.image(), sweep.sweptAt())) {
                    newlyOutOfDate.add(scoped);
                }
            }
        }

        // Whatever is not in this set is forgotten: up to date now, or no longer swept at all.
        knownOutOfDate.saveOutOfDate(stillOutOfDate);
        // Stable order for the mail. By identity then image, because the machine's NAME is not held here —
        // it is supplied when the alert is written, so it is not available to sort on.
        newlyOutOfDate.sort(Comparator.comparing(ScopedImage::machineId).thenComparing(ScopedImage::image));
        return newlyOutOfDate;
    }
}
