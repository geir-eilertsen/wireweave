package net.vaier.domain;

/**
 * What a held {@code Enrolment ticket} is owed at a given instant: it is still waiting, its config
 * is ready, or there is nothing here for it. The third answer covers an unknown ticket, an expired
 * request and a ticket presented against someone else's request alike — a caller learns only that
 * it has nothing coming.
 *
 * @param configFile the enrolled peer's installable WireGuard config, present only on an approval.
 */
public record EnrolmentVerdict(Standing standing, String configFile) {

    public enum Standing { PENDING, APPROVED, GONE }

    public EnrolmentVerdict {
        if (standing == Standing.APPROVED && (configFile == null || configFile.isBlank())) {
            throw new IllegalArgumentException("An approved enrolment must carry the config it approved");
        }
    }

    public static EnrolmentVerdict gone() {
        return new EnrolmentVerdict(Standing.GONE, null);
    }

    public static EnrolmentVerdict pending() {
        return new EnrolmentVerdict(Standing.PENDING, null);
    }

    public static EnrolmentVerdict approved(String configFile) {
        return new EnrolmentVerdict(Standing.APPROVED, configFile);
    }

    public boolean isGone() {
        return standing == Standing.GONE;
    }

    public boolean isPending() {
        return standing == Standing.PENDING;
    }

    public boolean isApproved() {
        return standing == Standing.APPROVED;
    }
}
