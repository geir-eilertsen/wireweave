package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The <b>moving tag</b> rule: which image tags are channels that move on their own, and are therefore never
 * worth an <b>update-available alert</b>.
 *
 * <p>The rule is about <b>when the digest changed</b>, not about how many sweeps ran. Counting sweeps was the
 * first attempt and it could not survive this project's own habits: every boot sweeps two minutes in, the
 * registry cache dies with the process, and a box that redeploys several times a day therefore fed the
 * history a pile of unchanged answers that read as the tag settling.
 */
class RegistryDigestHistoryTest {

    private static final String NETDATA = "netdata/netdata:latest";
    private static final Instant DAY_ONE = Instant.parse("2026-09-01T01:40:00Z");

    private static Instant days(double n) {
        return DAY_ONE.plusSeconds((long) (n * 86400));
    }

    /** The history after a sweep at {@code at} that answered {@code digest} for {@link #NETDATA}. */
    private static RegistryDigestHistory swept(RegistryDigestHistory history, String digest, Instant at) {
        return history.after(Set.of(NETDATA), Map.of(NETDATA, digest), at);
    }

    /** A nightly answered a day apart, three days running — the shape of the netdata channel. */
    private static RegistryDigestHistory aNightly() {
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:n1", days(0));
        history = swept(history, "sha256:n2", days(1));
        return swept(history, "sha256:n3", days(2));
    }

    // --- is this tag moving? ---------------------------------------------------------------------------

    @Test
    void aTagNoSweepHasAnsweredForIsNotMoving() {
        assertThat(RegistryDigestHistory.empty().isMoving(NETDATA, days(0))).isFalse();
    }

    @Test
    void aDigestThatNeverChangedIsNotMoving() {
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:a", days(0));
        history = swept(history, "sha256:a", days(1));
        history = swept(history, "sha256:a", days(2));

        assertThat(history.isMoving(NETDATA, days(2))).isFalse();
    }

    @Test
    void oneChangeIsNotMoving_becauseOneReleaseIsWhatTheAlertIsFor() {
        // The ordinary case the whole feature must keep working: a settled tag cut a release. That is
        // exactly one change, and it is news.
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:a", days(0));
        history = swept(history, "sha256:b", days(1));

        assertThat(history.isMoving(NETDATA, days(1))).isFalse();
    }

    @Test
    void twoChangesADayApartAreMoving() {
        assertThat(aNightly().isMoving(NETDATA, days(2))).isTrue();
    }

    @Test
    void aTagThatChangedThreeTimesOverThreeMonthsIsNotMoving() {
        // A perfectly ordinary release cadence. Counting changes alone would call this a channel and
        // silence the one alert per quarter that the operator actually wants.
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:a", days(0));
        history = swept(history, "sha256:b", days(30));
        history = swept(history, "sha256:c", days(60));

        assertThat(history.isMoving(NETDATA, days(60))).isFalse();
    }

    @Test
    void aChannelThatWentQuietStopsBeingMoving_withoutAnySweepSayingSo() {
        // Upstream stopped building nightlies. Nothing new is recorded — an unchanged answer records
        // nothing at all — so the only thing that can end the suppression is the clock.
        assertThat(aNightly().isMoving(NETDATA, days(2).plus(Duration.ofDays(3)))).isFalse();
    }

    @Test
    void anUnchangedAnswerNeitherAppendsNorSettlesTheTag() {
        // THE regression. Every boot sweeps two minutes in and the registry cache died with the process, so
        // an afternoon redeploy re-asked and got the morning's digest back. Recording that as an answer read
        // as the tag having settled, and this box redeploys several times a day.
        RegistryDigestHistory history = aNightly();

        RegistryDigestHistory afterARedeploy = swept(history, "sha256:n3", days(2.2));
        RegistryDigestHistory afterACheck = swept(afterARedeploy, "sha256:n3", days(2.3));

        assertThat(afterACheck.isMoving(NETDATA, days(2.4))).isTrue();
        assertThat(afterACheck.answers().get(NETDATA)).hasSize(3);
    }

    @Test
    void movingImages_namesEveryTagThatMoves_andNoOther() {
        Set<String> both = Set.of(NETDATA, "vaultwarden/server:latest");
        RegistryDigestHistory history = RegistryDigestHistory.empty()
            .after(both, Map.of(NETDATA, "sha256:a", "vaultwarden/server:latest", "sha256:v"), days(0))
            .after(both, Map.of(NETDATA, "sha256:b", "vaultwarden/server:latest", "sha256:v"), days(1))
            .after(both, Map.of(NETDATA, "sha256:c", "vaultwarden/server:latest", "sha256:v"), days(2));

        assertThat(history.movingImages(days(2))).containsExactly(NETDATA);
    }

    // --- what advances the history ---------------------------------------------------------------------

    @Test
    void anUnansweredSweepRecordsNothing_soAnUnreachableRegistryIsNotAChange() {
        RegistryDigestHistory history = RegistryDigestHistory.empty()
            .after(Set.of(NETDATA), Map.of(NETDATA, "sha256:a"), days(0))
            .after(Set.of(NETDATA), Map.of(), days(1))
            .after(Set.of(NETDATA), Map.of(NETDATA, "sha256:b"), days(2));

        assertThat(history.answers().get(NETDATA))
            .extracting(RegistryDigestHistory.Answer::digest)
            .containsExactly("sha256:a", "sha256:b");
    }

    @Test
    void eachEntryRemembersWhenTheDigestWasFirstSeen_notWhenItWasLastConfirmed() {
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:a", days(0));
        history = swept(history, "sha256:a", days(3));

        assertThat(history.answers().get(NETDATA))
            .singleElement()
            .extracting(RegistryDigestHistory.Answer::firstSeen).isEqualTo(days(0));
    }

    @Test
    void onlyTheLastThreeDistinctDigestsAreKept_becauseTwoChangesIsTheWholeQuestion() {
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:a", days(0));
        history = swept(history, "sha256:b", days(1));
        history = swept(history, "sha256:c", days(2));
        history = swept(history, "sha256:d", days(3));

        assertThat(history.answers().get(NETDATA))
            .extracting(RegistryDigestHistory.Answer::digest)
            .containsExactly("sha256:b", "sha256:c", "sha256:d");
    }

    @Test
    void anImageMissingFromOneSweepKeepsItsHistory_becauseAPeerCanBeUnreachable() {
        // An unreachable peer reports an EMPTY container list, so every image on it vanishes from the sweep.
        // Dropping the history there costs the suppression and mails the operator on the peer's return.
        RegistryDigestHistory history = aNightly()
            .after(Set.of(), Map.of(), days(3));

        assertThat(history.isMoving(NETDATA, days(3))).isTrue();
    }

    @Test
    void anImageNothingHasRunForAWeekIsDropped_soTheFileDoesNotGrowForever() {
        RegistryDigestHistory history = aNightly()
            .after(Set.of(), Map.of(), days(2).plus(Duration.ofDays(8)));

        assertThat(history.answers()).isEmpty();
    }

    @Test
    void anImageSweptButNeverAnsweredForKeepsNoEmptyEntry() {
        RegistryDigestHistory history =
            RegistryDigestHistory.empty().after(Set.of(NETDATA), Map.of(), days(0));

        assertThat(history.answers()).isEmpty();
    }

    @Test
    void theHistoryIsImmutable_soAStoreCannotBeEditedThroughWhatItHandedOut() {
        RegistryDigestHistory history = swept(RegistryDigestHistory.empty(), "sha256:a", days(0));

        assertThat(history.answers()).isUnmodifiable();
        assertThat(history.answers().get(NETDATA)).isUnmodifiable();
    }

    @Test
    void itIsBuiltFromWhatAStoreLoaded_soARestartResumesTheSameHistory() {
        RegistryDigestHistory loaded = new RegistryDigestHistory(Map.of(NETDATA, List.of(
            new RegistryDigestHistory.Answer("sha256:a", days(0)),
            new RegistryDigestHistory.Answer("sha256:b", days(1)),
            new RegistryDigestHistory.Answer("sha256:c", days(2)))));

        assertThat(loaded.isMoving(NETDATA, days(2))).isTrue();
    }
}
