package net.fjordomatic.domain;

import java.util.List;

/**
 * What the boot-time wildcard check found, and how to say it to an operator. The whole point of the
 * check is that "did I set DNS up right?" stops being a question and becomes a sentence Fjord states,
 * so {@link #message()} is written in plain language with the one action to take.
 *
 * @param status            what was found
 * @param probeFqdn         the random name that was looked up, e.g. {@code 9f3c1a.b21d70.example.com}
 *                          — two labels deep, because that is the depth Fjord publishes at (see
 *                          {@link WildcardDns})
 * @param expectedAddress   this server's public IP address, or {@code null} when it could not be determined
 * @param observedAddresses what a public resolver answered with, empty when the name does not resolve
 */
public record WildcardDnsReport(
    WildcardDnsStatus status,
    String probeFqdn,
    String expectedAddress,
    List<String> observedAddresses
) {

    /** Wording used in place of an address when this server's own public IP could not be determined. */
    private static final String UNKNOWN_ADDRESS = "this server's public IP address";

    public WildcardDnsReport {
        observedAddresses = observedAddresses == null ? List.of() : List.copyOf(observedAddresses);
    }

    /**
     * The record the operator is being asked about, e.g. {@code *.example.com}. Strips <em>two</em>
     * labels, because the probe is two labels deep — the operator still creates exactly one record.
     */
    public String wildcardName() {
        int firstDot = probeFqdn.indexOf('.');
        if (firstDot < 0) return "*." + probeFqdn;
        int secondDot = probeFqdn.indexOf('.', firstDot + 1);
        return secondDot < 0 ? "*." + probeFqdn.substring(firstDot + 1) : "*" + probeFqdn.substring(secondDot);
    }

    /** A sentence an operator can act on without knowing anything about how Fjord works. */
    public String message() {
        return switch (status) {
            case COVERED -> "Wildcard DNS is working — " + wildcardName() + " resolves to "
                + observed() + ".";
            case NOT_RESOLVING -> "Wildcard DNS is not set up. Create one record — " + wildcardName()
                + " A " + (expectedAddress == null ? UNKNOWN_ADDRESS : expectedAddress)
                + " — and every service Fjord publishes will resolve.";
            case RESOLVES_ELSEWHERE -> "Wildcard DNS points somewhere else — " + wildcardName()
                + " resolves to " + observed() + ", but this server is " + expectedAddress
                + ". Point the record at " + expectedAddress
                + " and every service Fjord publishes will resolve.";
            case UNCONFIRMED -> wildcardName() + " resolves to " + observed()
                + ", but Fjord could not determine this server's own public address, so it cannot "
                + "confirm that is right.";
        };
    }

    private String observed() {
        return String.join(", ", observedAddresses);
    }
}
