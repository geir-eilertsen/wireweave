package net.vaier.domain;

/**
 * The address a request really came from. Two surfaces need it — the launchpad, deciding whether to hand
 * out a LAN-direct URL, and the forward-auth check, recording where an allowed access came from — and both
 * face the same question: {@code X-Forwarded-For} is a header, so anyone can write one.
 *
 * <p><b>Which hop to believe is a decision, and this is where it is made.</b> The header is trusted only
 * when the immediate connection came from inside the trusted proxy CIDR — Vaier's own Traefik. From
 * anywhere else, only the address the socket itself reports means anything, because a request that reached
 * the container directly could name any client it liked. Inside that gate the leftmost hop is the original
 * client; every hop after it is a proxy that appended itself.
 *
 * <p><b>The leftmost hop is only trustworthy because Traefik strips the header.</b> Vaier sets no
 * {@code forwardedHeaders.trustedIPs}, so Traefik treats every inbound connection as untrusted and deletes
 * any client-supplied {@code X-Forwarded-*} before writing its own — a spoofed value never survives to
 * reach this class. Configure {@code trustedIPs} and that stops being true: the leftmost hop becomes
 * client-controlled, and this rule has to change to walk the chain from the right instead.
 *
 * <p>It sat as a private method on {@code LaunchpadRestController} until a second caller needed it. A copy
 * would have been the drift: two places to get the trust boundary right, and only one of them reviewed.
 */
public record CallerIp(String value) {

    public static CallerIp of(String remoteAddress, String forwardedFor, String trustedProxyCidr) {
        String hop = forwardedHop(forwardedFor);
        return new CallerIp(hop != null && fromTrustedProxy(remoteAddress, trustedProxyCidr)
            ? hop : remoteAddress);
    }

    /**
     * The leftmost hop, and only when it is an IP literal. Defence in depth: what leaves here is handed to
     * the geolocation port, which resolves a name via DNS — a blocking lookup on the endpoint that
     * authenticates every request to every gated service. Traefik makes a hostname unreachable here today;
     * refusing one keeps that true if it ever stops being.
     *
     * <p>{@link Cidr#isIpv4} means an IPv6 client behind the proxy falls back to the socket address rather
     * than being named. Vaier's stack is IPv4 throughout — one wildcard A record, an IPv4 tunnel — so that
     * costs a dot on a map at worst, and it is the safe direction to be wrong in.
     */
    private static String forwardedHop(String forwardedFor) {
        if (forwardedFor == null || forwardedFor.isBlank()) return null;
        int comma = forwardedFor.indexOf(',');
        String hop = (comma < 0 ? forwardedFor : forwardedFor.substring(0, comma)).trim();
        return Cidr.isIpv4(hop) ? hop : null;
    }

    /**
     * A trusted-proxy CIDR that will not parse is a misconfiguration, and the safe answer to "may I believe
     * this header?" is no. It used to throw straight out of the launchpad, which has no try/catch — one
     * typo in the property and the page 500s.
     */
    private static boolean fromTrustedProxy(String remoteAddress, String trustedProxyCidr) {
        if (remoteAddress == null || trustedProxyCidr == null) return false;
        try {
            return Cidr.parse(trustedProxyCidr).contains(remoteAddress);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
