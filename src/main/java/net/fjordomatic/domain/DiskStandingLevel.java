package net.fjordomatic.domain;

/**
 * How a machine's disks stand right now, in the three steps a fleet listing can actually show: nothing to
 * say, worth an eye, or in trouble. It is the severity half of a {@link MachineDiskStanding}, and it is a
 * <b>domain</b> decision rather than a colour chosen in a stylesheet — the mark on a machine card and any
 * other surface that ever grows one must not be free to disagree about when a disk is in trouble.
 *
 * <p>There is deliberately no {@code UNKNOWN}. A machine Fjord has not read yet has no standing at all —
 * see {@link MachineDiskStanding#of} — because the one failure this fleet has already been bitten by is
 * absence being read as health.
 */
public enum DiskStandingLevel {

    /** Every watched filesystem has room, and none is near the threshold it is judged against. */
    CLEAR,

    /**
     * No watched filesystem is over its threshold, but the worst one is within a {@link DiskPressureBand}
     * of it — close enough that the operator would rather know now than be emailed about it later.
     */
    CLOSING,

    /** At least one watched filesystem is in <b>remote disk pressure</b> — above the threshold it is judged against. */
    BREACHING
}
