package net.vaier.domain;

import net.vaier.adapter.driven.ImageUpdateStateFileAdapter;
import net.vaier.adapter.driven.InMemoryImageUpdateStateAdapter;
import net.vaier.domain.port.ForStoringContainerSnapshots;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
        return new ImageUpdateTracker(new InMemoryImageUpdateStateAdapter(),
            mock(ForStoringContainerSnapshots.class));
    }

    /**
     * A tracker whose memory is the file in {@link #configDir}. Two of these in one test are two runs of
     * Vaier over the same config directory — which is the only way to write a test about a restart.
     */
    private ImageUpdateTracker trackerSurvivingRestart() {
        return new ImageUpdateTracker(new ImageUpdateStateFileAdapter(configDir.toString()),
            mock(ForStoringContainerSnapshots.class));
    }

    /** A sweep that judged containers but resolved no registry answer — most tests here are about the latch. */
    private static ImageUpdateSweep.Result verdicts(Object... pairs) {
        Map<ScopedImage, UpdateAvailability> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            ScopedImage key = pairs[i] instanceof ScopedImage s ? s : si((String) pairs[i]);
            map.put(key, (UpdateAvailability) pairs[i + 1]);
        }
        return new ImageUpdateSweep.Result(map, Map.of(), DAY_ONE);
    }

    /** A sweep of already-built verdicts, with no registry answers — as {@link #verdicts} but keyed. */
    private static ImageUpdateSweep.Result judged(Object... pairs) {
        Map<ScopedImage, UpdateAvailability> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((ScopedImage) pairs[i], (UpdateAvailability) pairs[i + 1]);
        }
        return new ImageUpdateSweep.Result(map, Map.of(), DAY_ONE);
    }

    private static final Instant DAY_ONE = Instant.parse("2026-09-01T01:40:00Z");

    private static Instant days(double n) {
        return DAY_ONE.plusSeconds((long) (n * 86400));
    }

    /** One sweep of a single image at {@code at}: what Vaier decided, and the digest the registry served. */
    private static ImageUpdateSweep.Result sweep(String image, UpdateAvailability verdict, String digest,
                                                 Instant at) {
        return new ImageUpdateSweep.Result(Map.of(si(image), verdict), Map.of(image, digest), at);
    }

    // --- #57 slice 3: the operator's own check folds in exactly as the daily sweep does -----------------
    //
    // A check used to be allowed only to CLEAR an image's alert state, never to record one, so that the
    // daily sweep would still find the edge and mail it. That left the operator looking at a fresh yellow
    // mark with the mail eight hours away. The check now latches what it finds, because it mails what it
    // finds — see UpdateCheckOutcome.newlyOutOfDate — and the sweep that follows must then stay quiet.

    @Test
    void anImageFoundUpToDateByACheck_isAlertableAgainIfItGoesStaleLater() {
        // The silencing bug. A check that confirms a pull must forget the image, or the next genuine
        // staleness finds it already latched and fires no edge — the operator's own diligence would have
        // switched off a future alarm.
        ImageUpdateTracker tracker = tracker();
        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .containsExactly(si("vaultwarden/server:latest"));        // reported once

        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UP_TO_DATE));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("it went stale again — that is news again")
            .containsExactly(si("vaultwarden/server:latest"));
    }

    @Test
    void anImageFoundStaleByACheck_isNewsToTheCheck_andNotAgainToTheSweepThatFollows() {
        // The check reports the edge, so it is the check's mail; the daily sweep then finds it latched and
        // stays silent. One verdict, one mail, whichever path learned it.
        ImageUpdateTracker tracker = tracker();

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("the check's own mail")
            .containsExactly(si("vaultwarden/server:latest"));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("the sweep the same evening")
            .isEmpty();
    }

    @Test
    void anUnknownVerdictFromACheckClearsNothing() {
        // A rate-limited registry is not evidence the operator pulled. Reading it as such would re-arm the
        // alert and re-mail them about an image they were already told about.
        ImageUpdateTracker tracker = tracker();
        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE));

        tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UNKNOWN));

        assertThat(tracker.update(verdicts("vaultwarden/server:latest", UpdateAvailability.UPDATE_AVAILABLE)))
            .as("nothing was learned, so nothing was cleared")
            .isEmpty();
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

        assertThat(tracker.update(judged(
            onApalveien, UpdateAvailability.UPDATE_AVAILABLE,
            onColina, UpdateAvailability.UP_TO_DATE)))
            .containsExactly(onApalveien);

        assertThat(tracker.update(judged(
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
        assertThat(tracker.update(judged(
            traefik, UpdateAvailability.UPDATE_AVAILABLE,
            pihole, UpdateAvailability.UP_TO_DATE)))
            .containsExactly(traefik);

        // The next sweep no longer carries Vaier's own stack at all.
        assertThat(tracker.update(judged(pihole, UpdateAvailability.UP_TO_DATE))).isEmpty();

        // And it stays silent — the mail this issue is about is not sent again by the sweep after it.
        assertThat(tracker.update(judged(pihole, UpdateAvailability.UP_TO_DATE))).isEmpty();
    }

    @Test
    void anImageThatComesBackToTheSweepStaleIsNewsAgain_notASilence() {
        ImageUpdateTracker tracker = tracker();
        // The other half of forgetting. An own-stack container that stopped being own-stack — renamed, or
        // moved to a peer — is a genuinely new situation, and a tracker still holding the old latch would
        // swallow the one alert that mattered.
        ScopedImage image = new ScopedImage("machine-1", "traefik:v3.6.14");
        tracker.update(judged(image, UpdateAvailability.UPDATE_AVAILABLE));
        tracker.update(judged());

        assertThat(tracker.update(judged(image, UpdateAvailability.UPDATE_AVAILABLE)))
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
        beforeRestart.update(verdicts("redis:7.2", UpdateAvailability.UP_TO_DATE));

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

    // --- the moving tag: a channel is not trouble, so it earns the mark and never the mail ---------------
    //
    // The rule is about WHEN the digest changed, not how many sweeps ran. Counting sweeps was defeated by
    // this project's own habits: every boot sweeps two minutes in and the registry cache dies with the
    // process, so an afternoon redeploy re-asked, got the morning's digest, and that read as the tag
    // settling. NETDATA is the case throughout — netdata:latest IS Docker Hub's :edge, a nightly.

    private static final String NETDATA = "netdata/netdata:latest";

    @Test
    void aTagThatMovesEveryNightIsMailedOnceAndThenNeverAgainWhileItKeepsMoving() {
        // The live incident, replayed with everything that really happens between two daily sweeps: an
        // afternoon redeploy (which sweeps two minutes into every boot) and the operator's own check after
        // they update. Exactly one mail — the first change, when nothing yet says the tag moves — and then
        // silence for as long as it keeps moving.
        ImageUpdateTracker tracker = tracker();
        List<ScopedImage> mails = new ArrayList<>();

        // Day 0: the operator is running the current nightly, and the day's noise says nothing new.
        mails.addAll(tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n1", days(0))));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n1", days(0.2)));   // redeploy
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n1", days(0.3)));   // check

        // Day 1: a new nightly. One change — the tag might simply have cut a release, so it is mailed.
        mails.addAll(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n2", days(1))));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n2", days(1.3)));   // updated

        // Day 2 and day 3: it moved again, and again. A channel. The mark stays; the mail stops.
        mails.addAll(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n3", days(2))));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n3", days(2.2)));   // redeploy
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n3", days(2.3)));   // updated
        mails.addAll(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n4", days(3))));

        assertThat(mails).containsExactly(si(NETDATA));
    }

    @Test
    void anUnchangedAnswerLeavesAMovingTagMoving_soARedeployCannotUnsuppressAChannel() {
        // The regression in one test. Two more sweeps on the same digest — a redeploy's boot sweep and an
        // operator's check, minutes apart — must not make a channel look settled.
        ImageUpdateTracker tracker = movingSince(days(2));

        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n3", days(2.1)));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n3", days(2.2)));

        assertThat(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n4", days(2.5)))).isEmpty();
    }

    @Test
    void aSettledTagThatChangesOnceIsStillMailed_becauseThatIsTheWholeFeature() {
        // vaultwarden's latest sat on the same digest for weeks and then cut a release. One change on a tag
        // that has not moved is exactly the #57 incident, and it must still arrive.
        ImageUpdateTracker tracker = tracker();
        String vaultwarden = "vaultwarden/server:latest";

        tracker.update(sweep(vaultwarden, UpdateAvailability.UP_TO_DATE, "sha256:v1", days(0)));
        tracker.update(sweep(vaultwarden, UpdateAvailability.UP_TO_DATE, "sha256:v1", days(1)));

        assertThat(tracker.update(
            sweep(vaultwarden, UpdateAvailability.UPDATE_AVAILABLE, "sha256:v2", days(2))))
            .containsExactly(si(vaultwarden));
    }

    @Test
    void aTagThatChangedThreeTimesInThreeMonthsIsMailedEveryTime() {
        // An ordinary quarterly release cadence. Counting changes alone would call this a channel and
        // swallow the one alert a quarter the operator actually wants.
        ImageUpdateTracker tracker = tracker();
        String traefik = "traefik:v3";

        tracker.update(sweep(traefik, UpdateAvailability.UP_TO_DATE, "sha256:t1", days(0)));
        assertThat(tracker.update(sweep(traefik, UpdateAvailability.UPDATE_AVAILABLE, "sha256:t2", days(30))))
            .containsExactly(si(traefik));
        tracker.update(sweep(traefik, UpdateAvailability.UP_TO_DATE, "sha256:t2", days(31)));

        assertThat(tracker.update(sweep(traefik, UpdateAvailability.UPDATE_AVAILABLE, "sha256:t3", days(60))))
            .as("three changes, but months apart — a release cadence, not a channel")
            .containsExactly(si(traefik));
    }

    @Test
    void aMovingTagIsStillLatched_soItIsNotMailedRetroactivelyOnceItSettles() {
        // The suppressed change must still be RECORDED as known out of date. If it were not, the first sweep
        // after the tag stopped moving would find an unlatched out-of-date image and mail the operator about
        // a change from days ago — the silence would end with a backlog rather than with the next real news.
        ImageUpdateTracker tracker = movingSince(days(2));
        assertThat(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n4", days(3)))).isEmpty();

        assertThat(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n4", days(4)))).isEmpty();
    }

    @Test
    void aChannelThatWentQuietIsMailedOnItsNextChange() {
        // Upstream stopped building nightlies. Nothing records that — an unchanged answer records nothing —
        // so only the clock can end the suppression, and it does.
        ImageUpdateTracker tracker = movingSince(days(2));

        assertThat(tracker.update(
            sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n4", days(9))))
            .containsExactly(si(NETDATA));
    }

    @Test
    void theDigestHistorySurvivesARestart_soAChannelIsNotRelearnedEveryDeploy() {
        // This project rebuilds and redeploys several times a day. A history that died with the process
        // would never reach three distinct digests, and the suppression would never engage at all.
        ImageUpdateTracker before = trackerSurvivingRestart();
        before.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n1", days(0)));
        before.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n2", days(1)));

        ImageUpdateTracker after = trackerSurvivingRestart();

        assertThat(after.update(sweep(NETDATA, UpdateAvailability.UPDATE_AVAILABLE, "sha256:n3", days(2))))
            .isEmpty();
    }

    @Test
    void theMovingTagsArePublishedToTheContainerSnapshots_soTheExplorerCanSayWhyTheMailStopped() {
        // The mark stays; only the mail goes. A silently suppressed alert would read as Vaier having missed
        // it, so the Explorer says the tag is a channel — which it can only do if it is told which are.
        ForStoringContainerSnapshots snapshots = mock(ForStoringContainerSnapshots.class);
        ImageUpdateTracker tracker =
            new ImageUpdateTracker(new InMemoryImageUpdateStateAdapter(), snapshots);

        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n1", days(0)));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n2", days(1)));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n3", days(2)));

        verify(snapshots).storeMovingTags(Set.of(NETDATA));
    }

    /** A tracker that has watched {@link #NETDATA} change on three consecutive days, ending at {@code at}. */
    private static ImageUpdateTracker movingSince(Instant at) {
        ImageUpdateTracker tracker = tracker();
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n1",
            at.minusSeconds(2 * 86400)));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n2",
            at.minusSeconds(86400)));
        tracker.update(sweep(NETDATA, UpdateAvailability.UP_TO_DATE, "sha256:n3", at));
        return tracker;
    }
}
