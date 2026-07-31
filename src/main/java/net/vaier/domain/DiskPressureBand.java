package net.vaier.domain;

/**
 * How bad a filesystem's <b>remote disk pressure</b> has got, in five-point steps: 80, 85, 90, 95, 100. It
 * is the unit Vaier escalates on — a filesystem in pressure is alerted about once per band, and again only
 * when it climbs into a higher one.
 *
 * <p><b>Why a band and not a timer.</b> A watched filesystem that is already above its threshold has to be
 * re-notified <em>somehow</em>, or the first email is the only one the operator ever gets for a disk that
 * goes on filling. The obvious answer — re-send every N hours — is exactly the heartbeat noise this project
 * refuses to produce: it pages on the passage of time rather than on anything having changed. A band pages
 * on the disk getting materially worse and on nothing else. 86% → 89% is silence; 89% → 91% is an email.
 *
 * <p>Five points is deliberately coarse. Finer and a disk wobbling either side of an edge would page on
 * every wobble; coarser and the jump from "uncomfortable" to "out of space" would pass unremarked.
 *
 * @param floorPercent the step's lower edge — a multiple of {@link #STEP}, 0 through 100
 */
public record DiskPressureBand(int floorPercent) {

    /** The width of a band, in percentage points. */
    public static final int STEP = 5;

    public DiskPressureBand {
        if (floorPercent < 0 || floorPercent > 100) {
            throw new IllegalArgumentException("Disk pressure band must be 0–100%, was " + floorPercent);
        }
        if (floorPercent % STEP != 0) {
            // Guards the persisted form as much as the code: a hand-edited disk-pressure.yml carrying 87 is
            // a band nothing could ever have produced, and reading it back would shift the whole ladder.
            throw new IllegalArgumentException(
                "Disk pressure band must be a multiple of " + STEP + "%, was " + floorPercent);
        }
    }

    /** The band a {@code df} reading of {@code usedPercent} falls into — the step at or below it. */
    public static DiskPressureBand of(int usedPercent) {
        if (usedPercent < 0 || usedPercent > 100) {
            throw new IllegalArgumentException("A disk reading must be 0–100%, was " + usedPercent);
        }
        return new DiskPressureBand(usedPercent / STEP * STEP);
    }

    /** Whether this band is worse than {@code other} — i.e. worth telling admins about again. */
    public boolean isHigherThan(DiskPressureBand other) {
        return other != null && floorPercent > other.floorPercent;
    }

    @Override
    public String toString() {
        return floorPercent + "%";
    }
}
