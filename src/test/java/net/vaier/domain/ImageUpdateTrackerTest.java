package net.vaier.domain;

import net.vaier.adapter.driven.ImageUpdateStateFileAdapter;
import net.vaier.adapter.driven.InMemoryImageUpdateStateAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageUpdateTrackerTest {

    private static final String HOST = "Vaier server";

    /** An image on the default host, so a test that does not care about machines reads as before. */
    private static ScopedImage si(String image) {
        return new ScopedImage(HOST, image);
    }

    @TempDir
    Path configDir;

    /** A tracker whose memory dies with it — every test that is not about a restart wants this one. */
    private static ImageUpdateTracker tracker() {
        return new ImageUpdateTracker(new InMemoryImageUpdateStateAdapter());
    }

    /**
     * A tracker whose memory is the file in {@link #configDir}. Two of these in one test are two runs of
     * Vaier over the same config directory — which is the only way to write a test about a restart.
     */
    private ImageUpdateTracker trackerSurvivingRestart() {
        return new ImageUpdateTracker(new ImageUpdateStateFileAdapter(configDir.toString()));
    }

    private static Map<ScopedImage, UpdateAvailability> verdicts(Object... pairs) {
        Map<ScopedImage, UpdateAvailability> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            ScopedImage key = pairs[i] instanceof ScopedImage s ? s : si((String) pairs[i]);
            map.put(key, (UpdateAvailability) pairs[i + 1]);
        }
        return map;
    }

    // --- #57 slice 3: what an operator-driven check may do to the alert state -------------------------
    //
    // The forced check is a partial-purpose observation: the operator is confirming their own pull, not
    // standing in for the mailer. So it may CLEAR an image's alert state and may never CONSUME one. That
    // asymmetry is the whole rule, and both halves of it are a real bug if dropped — see the two tests below.

    @Test
    void anImageFoundUpToDateByAForcedCheck_isAlertableAgainIfItGoesStaleLater() {
        // The silencing bug. Without this, a manual check that confirms a pull leaves the tracker still
        // believing the image is out of date — so when it genuinely goes stale again, the edge never fires
        // and the operator is never told. A button that quietly disables a future alarm is worse than no
        // button: they would trust a signal that had been switched off by their own diligence.
        ImageUpdateTracker tracker = tracker();
        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("vaultwarden/server:latest"));        // reported once

        tracker.clearUpToDate(verdicts("vaultwarden/server:latest", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("it went stale again — that is news again")
            .containsExactly(si("vaultwarden/server:latest"));
    }

    @Test
    void aForcedCheckFindingAnImageStale_doesNotConsumeTheAlertTheMailerOwes() {
        // The swallowing bug, and the reason this is clearUpToDate rather than update(). If a forced check
        // recorded a NEWLY stale image as "seen", the daily sweep would then find previous=true and stay
        // silent — so clicking the button would have cost the operator the very email the feature exists to
        // send. A check may only ever clear good news; bad news stays the mailer's to break.
        ImageUpdateTracker tracker = tracker();

        tracker.clearUpToDate(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("the mail the forced check must not have eaten")
            .containsExactly(si("vaultwarden/server:latest"));
    }

    @Test
    void aForcedCheckDoesNotReMailAnImageAlreadyReported() {
        // The duplicate-mail rule. Still stale, already told them: clearing touches nothing, and the next
        // daily sweep still finds previous=true and stays quiet.
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.clearUpToDate(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .isEmpty();
    }

    @Test
    void anUnknownVerdictFromAForcedCheckClearsNothing() {
        // Same reasoning as update()'s: a rate-limited registry is not evidence the operator pulled. Reading
        // it as such would re-arm the alert and re-mail them about an image they were already told about.
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.clearUpToDate(verdicts("vaultwarden/server:latest", UpdateAvailability.UNKNOWN));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("nothing was learned, so nothing was cleared")
            .isEmpty();
    }

    @Test
    void clearingIsSafeOnAnImageTheTrackerHasNeverSeen() {
        ImageUpdateTracker tracker = tracker();

        tracker.clearUpToDate(verdicts("redis:7.2", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("redis:7.2"));
    }

    @Test
    void reportsAnImageThatIsAlreadyOutOfDateOnTheVeryFirstSweep() {
        // Deliberately NOT baseline-quiet, unlike the peer/disk trackers. The #57 incident was an image that
        // was *already* stale: if a first sweep that has never seen the image stayed silent, the one case
        // this feature exists for would be the one case it never reports. "First" means first ever now that
        // the state is persisted — see the restart tests below for what a first sweep after a REBOOT does.
        ImageUpdateTracker tracker = tracker();

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("vaultwarden/server:latest"));
    }

    @Test
    void staysSilentWhileTheSameImageRemainsOutOfDate() {
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .isEmpty();
    }

    @Test
    void reportsOnlyTheNewlyOutOfDateImagesInASweep() {
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE, "b:1", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts(
            "a:1", UpdateAvailability.UPDATE_AVAILABLE,
            "b:1", UpdateAvailability.UPDATE_AVAILABLE,
            "c:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("b:1"), si("c:1"));
    }

    @Test
    void staysSilentWhenNothingChanged() {
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UP_TO_DATE))).isEmpty();
    }

    @Test
    void reportsAgainOnceAPulledImageGoesStaleAnew() {
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));
        tracker.update(verdicts("a:1", UpdateAvailability.UP_TO_DATE));   // operator pulled

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("a:1"));
    }

    @Test
    void anUnknownVerdictNeitherReportsNorForgetsWhatWasKnown() {
        // The registry went unreachable for a sweep. That is not the operator pulling the image: when it comes
        // back still outdated, they must not be re-mailed about an image they were already told about.
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UNKNOWN))).isEmpty();
        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE))).isEmpty();
    }

    @Test
    void anImageThatIsUnknownFromTheStartIsNeverReported() {
        ImageUpdateTracker tracker = tracker();

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UNKNOWN))).isEmpty();
    }

    @Test
    void forgetsAnImageThatIsNoLongerRunningAnywhere() {
        // The container was removed; if that image ever comes back stale it is news again.
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.update(verdicts());

        assertThat(tracker.update(verdicts("a:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("a:1"));
    }

    @Test
    void reportsNewlyOutOfDateImagesInAStableLabelOrder() {
        ImageUpdateTracker tracker = tracker();

        assertThat(tracker.update(verdicts(
            "zeta:1", UpdateAvailability.UPDATE_AVAILABLE,
            "alpha:1", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("alpha:1"), si("zeta:1"));
    }

    // --- machine-aware tracking (#57 refinement) -----------------------------------------------------

    @Test
    void theSameImageGoingStaleOnASecondMachineIsReportedAsNewlyOutOfDate() {
        // The missed-alert bug this refinement fixes. vaultwarden goes stale on Apalveien 5 and is reported.
        // Later the same tag goes stale on Colina 27 too. Keyed by image string alone, the tracker would find
        // previous=true from Apalveien 5's edge and stay silent — the operator would never learn Colina 27
        // also needs pulling. Scoping to the machine makes the second machine its own edge.
        ScopedImage onApalveien = new ScopedImage("Apalveien 5", "vaultwarden/server:latest");
        ScopedImage onColina = new ScopedImage("Colina 27", "vaultwarden/server:latest");
        ImageUpdateTracker tracker = tracker();

        assertThat(tracker.update(Map.of(
            onApalveien, UpdateAvailability.UPDATE_AVAILABLE,
            onColina, UpdateAvailability.UP_TO_DATE)))
            .containsExactly(onApalveien);

        assertThat(tracker.update(Map.of(
            onApalveien, UpdateAvailability.UPDATE_AVAILABLE,
            onColina, UpdateAvailability.UPDATE_AVAILABLE)))
            .as("Colina 27 is newly out of date even though Apalveien 5 already was")
            .containsExactly(onColina);
    }

    // --- an image that leaves the sweep entirely (#353) ---

    @Test
    void anImageThatLeavesTheSweepIsForgotten_ratherThanStayingLatchedAsAlerted() {
        ImageUpdateTracker tracker = tracker();
        // #353 drops Vaier's own stack from the sweep. An image that had already alerted must not sit in
        // here as "alerted" forever: the tracker holds it only to suppress a repeat, and there is nothing
        // left to repeat about an image nobody is judging any more.
        ScopedImage traefik = new ScopedImage("machine-1", "traefik:v3.6.14");
        ScopedImage pihole = new ScopedImage("machine-1", "pihole/pihole:latest");
        assertThat(tracker.update(Map.of(
            traefik, UpdateAvailability.UPDATE_AVAILABLE,
            pihole, UpdateAvailability.UP_TO_DATE)))
            .containsExactly(traefik);

        // The next sweep no longer carries Vaier's own stack at all.
        assertThat(tracker.update(Map.of(pihole, UpdateAvailability.UP_TO_DATE))).isEmpty();

        // And it stays silent — the mail this issue is about is not sent again by the sweep after it.
        assertThat(tracker.update(Map.of(pihole, UpdateAvailability.UP_TO_DATE))).isEmpty();
    }

    @Test
    void anImageThatComesBackToTheSweepStaleIsNewsAgain_notASilence() {
        ImageUpdateTracker tracker = tracker();
        // The other half of forgetting. An own-stack container that stopped being own-stack — renamed, or
        // moved to a peer — is a genuinely new situation, and a tracker still holding the old latch would
        // swallow the one alert that mattered.
        ScopedImage image = new ScopedImage("machine-1", "traefik:v3.6.14");
        tracker.update(Map.of(image, UpdateAvailability.UPDATE_AVAILABLE));
        tracker.update(Map.of());

        assertThat(tracker.update(Map.of(image, UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(image);
    }

    // --- surviving a restart -------------------------------------------------------------------------
    //
    // The live defect. The latch was a plain in-memory map, so every restart it came back empty and the
    // first sweep read images that were genuinely still out of date as *newly* out of date. With a
    // build-and-deploy on every change, that is the same two or three images mailed several times a day.

    @Test
    void anImageAlreadyKnownOutOfDateIsNotAnnouncedAgainAfterARestart() {
        ImageUpdateTracker beforeRestart = trackerSurvivingRestart();
        assertThat(beforeRestart.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("the one mail the operator should get about it")
            .containsExactly(si("vaultwarden/server:latest"));

        // Vaier restarts. Nothing is shared between the two trackers except the config directory.
        ImageUpdateTracker afterRestart = trackerSurvivingRestart();

        assertThat(afterRestart.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("still out of date, already told them — a restart is not news")
            .isEmpty();
    }

    @Test
    void anImageThatGoesStaleWhileVaierIsDownIsStillAnnouncedAfterTheRestart() {
        // The other half, and the reason this is persistence rather than a blanket first-sweep silence:
        // staying quiet on restart must cost nothing that was actually new.
        trackerSurvivingRestart().update(verdicts("redis:7.2", UpdateAvailability.UP_TO_DATE));

        ImageUpdateTracker afterRestart = trackerSurvivingRestart();

        assertThat(afterRestart.update(verdicts("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("redis:7.2"));
    }

    @Test
    void anImagePulledBeforeARestartIsAnnouncedAgainWhenItGoesStaleAnew() {
        // A regression must survive the round trip too, or persisting the latch would have bought silence
        // at the price of the alert itself.
        ImageUpdateTracker beforeRestart = trackerSurvivingRestart();
        beforeRestart.update(verdicts("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE));
        beforeRestart.update(verdicts("redis:7.2", UpdateAvailability.UP_TO_DATE));   // operator pulled

        assertThat(trackerSurvivingRestart().update(verdicts("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("redis:7.2"));
    }

    @Test
    void aCheckConfirmingAPullIsRememberedAcrossARestart() {
        // The operator's own check clears the state; if that clearing died with the process, the next boot
        // would still believe the image stale and swallow the next genuine edge.
        ImageUpdateTracker beforeRestart = trackerSurvivingRestart();
        beforeRestart.update(verdicts("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE));
        beforeRestart.clearUpToDate(verdicts("redis:7.2", UpdateAvailability.UP_TO_DATE));

        assertThat(trackerSurvivingRestart().update(verdicts("redis:7.2", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("redis:7.2"));
    }

    @Test
    void anImageThatLeftTheSweepBeforeARestartIsNewsAgainWhenItReturnsStale() {
        // Forgetting must persist as well as remembering: the container was gone, so nothing is owed about it.
        ImageUpdateTracker beforeRestart = trackerSurvivingRestart();
        beforeRestart.update(verdicts("traefik:v3.6.14", UpdateAvailability.UPDATE_AVAILABLE));
        beforeRestart.update(verdicts());

        assertThat(trackerSurvivingRestart().update(verdicts("traefik:v3.6.14", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("traefik:v3.6.14"));
    }

    @Test
    void anUnreachableRegistryOnTheFirstSweepAfterARestartLeavesTheStateStanding() {
        // UNKNOWN never rewrites what was known — and now it must not rewrite what was *persisted* either,
        // or one rate-limited sweep after a boot would re-arm every alert the operator already had.
        trackerSurvivingRestart().update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        ImageUpdateTracker afterRestart = trackerSurvivingRestart();
        assertThat(afterRestart.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UNKNOWN))).isEmpty();

        assertThat(afterRestart.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("the registry came back and said what was already known")
            .isEmpty();
    }
}
