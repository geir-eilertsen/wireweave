package net.vaier.domain;

/**
 * The mail admins get when a phone asks to join: who, the code they must match against the phone's
 * screen, how long it waits, and the one link that opens the approval — the same address the phone's
 * own "approve it here" uses, so it works from any signed-in browser.
 */
public record JoinRequestNotice(String name, String code, long minutesLeft) {

    public static JoinRequestNotice from(EnrolmentRequest request, long nowEpochMs) {
        return new JoinRequestNotice(request.name(), request.code(),
            Math.max(1, Math.round(request.secondsLeft(nowEpochMs) / 60.0)));
    }

    public String subject() {
        return "[Vaier] " + name + " wants to join — code " + code;
    }

    public String body(String baseDomain) {
        StringBuilder body = new StringBuilder();
        body.append(name).append(" is asking to join Vaier and is showing the code ").append(code).append(".\n\n")
            .append("Add it only if that is the code on the phone's screen. It waits ")
            .append(minutesLeft).append(minutesLeft == 1 ? " more minute" : " more minutes")
            .append(", then gives up.\n");
        if (baseDomain != null && !baseDomain.isBlank()) {
            body.append("\nAdd or refuse it: https://")
                .append(new VaierHostnames(baseDomain).vaierServerFqdn())
                .append("/explorer.html?approve=").append(code).append("\n");
        }
        return body.toString();
    }
}
