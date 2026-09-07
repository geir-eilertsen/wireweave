package net.vaier.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When each image tag was last seen on a new <b>registry digest</b>, and the one question that answers:
 * <b>is this a moving tag?</b>
 *
 * <p>Why: {@code netdata/netdata:latest} is Docker Hub's {@code :edge}, so the sweep truthfully found it out
 * of date every morning and mailed the operator every morning. A tag that moves on its own is a channel, not
 * trouble.
 *
 * <p><b>The rule is about when the digest changed, not how many sweeps ran.</b> Counting sweeps was defeated
 * by this project's own habits — every boot sweeps two minutes in and the registry cache dies with the
 * process, so a redeploy's re-ask returned the morning's digest and read as the tag settling. So an unchanged
 * answer records nothing, entries carry the instant a digest was <em>first</em> seen, and a channel that goes
 * quiet stops being moving on the clock alone.
 *
 * <p>Kept per image STRING, not per {@link ScopedImage}: a registry answers about a tag, not about a host.
 *
 * @param answers image string → its last {@link #REMEMBERED_ANSWERS} distinct digests, oldest first
 */
public record RegistryDigestHistory(Map<String, List<Answer>> answers) {

    /** How many distinct digests are kept per tag: two changes is the whole question, and that takes three. */
    public static final int REMEMBERED_ANSWERS = 3;

    /**
     * How far apart two changes may be and still read as a rhythm. A day and a half, so a nightly survives
     * a sweep running a few hours late, and a monthly release cadence never qualifies.
     */
    public static final Duration MOVING_WINDOW = Duration.ofHours(36);

    /**
     * History whose newest digest is older than this is dropped once the image is absent from a sweep —
     * measured from the last change, not the last sighting. An unreachable peer reports an empty container
     * list, so a single absence must not cost what took days to learn; a settled image is relearnt on its
     * next change anyway.
     */
    public static final Duration FORGET_AFTER = Duration.ofDays(7);

    /** One digest a registry served, and when Vaier first saw it there. */
    public record Answer(String digest, Instant firstSeen) {}

    /** Defensively copied, so what a store loaded — or handed out — cannot be edited from outside. */
    public RegistryDigestHistory {
        Map<String, List<Answer>> copy = new LinkedHashMap<>();
        if (answers != null) {
            answers.forEach((image, entries) -> {
                if (image != null && entries != null && !entries.isEmpty()) {
                    copy.put(image, List.copyOf(entries));
                }
            });
        }
        answers = Map.copyOf(copy);
    }

    /** What Vaier knows before any sweep has answered, and what an absent store loads as. */
    public static RegistryDigestHistory empty() {
        return new RegistryDigestHistory(Map.of());
    }

    /**
     * This history after a sweep at {@code sweptAt} that looked at {@code sweptImages} and got
     * {@code registryDigests} back.
     *
     * <p>Only a digest that differs from the last one recorded is appended — an unchanged or absent answer
     * changes nothing, so neither a redeploy's extra sweep nor an operator's check can make a channel look
     * settled. An image the sweep did not look at keeps its history until nothing has run it for
     * {@link #FORGET_AFTER}, because one absence is what an unreachable peer looks like.
     */
    public RegistryDigestHistory after(Set<String> sweptImages, Map<String, String> registryDigests,
                                       Instant sweptAt) {
        Map<String, List<Answer>> next = new LinkedHashMap<>();
        Instant forgetBefore = sweptAt.minus(FORGET_AFTER);
        answers.forEach((image, past) -> {
            if (!sweptImages.contains(image) && newestOf(past).firstSeen().isAfter(forgetBefore)) {
                next.put(image, past);
            }
        });
        for (String image : sweptImages) {
            List<Answer> past = answers.getOrDefault(image, List.of());
            String digest = registryDigests.get(image);
            if (digest == null || digest.isBlank()
                || (!past.isEmpty() && newestOf(past).digest().equals(digest))) {
                if (!past.isEmpty()) next.put(image, past);
                continue;
            }
            List<Answer> updated = new ArrayList<>(past);
            updated.add(new Answer(digest, sweptAt));
            while (updated.size() > REMEMBERED_ANSWERS) {
                updated.remove(0);
            }
            next.put(image, updated);
        }
        return new RegistryDigestHistory(next);
    }

    /**
     * Whether {@code image} is a <b>moving tag</b> as of {@code now}: it changed twice running, each change
     * within {@link #MOVING_WINDOW} of the next, and the last of them that recently.
     */
    public boolean isMoving(String image, Instant now) {
        List<Answer> entries = answers.get(image);
        if (entries == null || entries.size() < REMEMBERED_ANSWERS) {
            return false;
        }
        int last = entries.size() - 1;
        return closeEnough(entries.get(last - 2).firstSeen(), entries.get(last - 1).firstSeen())
            && closeEnough(entries.get(last - 1).firstSeen(), entries.get(last).firstSeen())
            && closeEnough(entries.get(last).firstSeen(), now);
    }

    /** Every image string that is a <b>moving tag</b> as of {@code now}. */
    public Set<String> movingImages(Instant now) {
        Set<String> moving = new LinkedHashSet<>();
        answers.keySet().forEach(image -> {
            if (isMoving(image, now)) moving.add(image);
        });
        return Set.copyOf(moving);
    }

    private static Answer newestOf(List<Answer> entries) {
        return entries.get(entries.size() - 1);
    }

    private static boolean closeEnough(Instant earlier, Instant later) {
        return !later.isAfter(earlier.plus(MOVING_WINDOW));
    }
}
