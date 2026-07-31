package net.vaier.domain;

import java.util.Arrays;
import java.util.Set;

/**
 * What kind of threat a {@link BlockDecision}'s CrowdSec scenario represents, and — the only reason this
 * distinction exists — whether it is worth an email.
 *
 * <p><b>Why Vaier classifies at all.</b> Slice 2 mailed every newly-appeared block decision. On day one
 * that was twenty-four mails, then more every sweep, and not one of them was actionable: a scanner being
 * banned is CrowdSec working exactly as intended, which is the opposite of the project's rule that a
 * notification means something is wrong or about to go wrong. Since the Security view and the Map now show
 * every decision regardless, routine bans have a better home than the inbox and the mail can narrow to the
 * ones a person would actually get up for.
 *
 * <p><b>The rule, stated plainly.</b> A scenario is a {@link #CREDENTIAL_ATTACK} when its name says
 * somebody was trying credentials — brute force, a login, an authentication endpoint. Everything else is
 * {@link #BLIND_SCANNING}: the internet rattling doorknobs it does not know are there. The distinction is
 * not about severity, it is about whether the source picked <em>this</em> fleet: a WordPress scan hits
 * every address on the internet the same way, whereas somebody grinding SSH logins has decided to spend
 * time on you specifically.
 *
 * <p><b>This is a judgement about scenario families, and it can be wrong.</b> It reads the words in the
 * scenario's own name rather than enumerating CrowdSec's hub, because the hub grows and third-party
 * collections ({@code LePresidente/grafana-bf}, {@code firix/authentik-auth-bf}) name their scenarios by
 * the same convention. A scenario whose name uses none of these words is treated as blind scanning and
 * stays <b>silent</b> — that default is deliberate and is the safe direction: a missed mail costs the
 * operator a look at the Security view, which is already open to them, while a wrong alarm costs the very
 * inbox noise this classification was written to remove.
 */
public enum ThreatKind {

    /**
     * Background radiation — untargeted probing, crawling, and vulnerability scanning that reaches every
     * address on the internet. Visible in the Security view and on the Map, never mailed.
     */
    BLIND_SCANNING,

    /**
     * Somebody working on getting in with credentials: brute force, password spraying, login endpoints.
     * Worth waking an admin for, and the only kind that becomes a breach attempt.
     */
    CREDENTIAL_ATTACK;

    /**
     * The words a CrowdSec scenario name uses when it caught somebody trying credentials rather than
     * rattling doorknobs — {@code ssh-bf}, {@code http-generic-bf}, {@code http-bf-wordpress_bf_xmlrpc},
     * {@code authentik-auth-bf}. {@code bf} is CrowdSec's own abbreviation for brute force and carries most
     * of the work; the rest are here so a collection that spells it out is not missed.
     *
     * <p>Matched as whole words against the scenario name split on its separators, never as substrings: a
     * substring test would make {@code http-backdoors-attempts} a credential attack on the strength of
     * {@code b}…{@code f} appearing in it, and the point of this list is that a reader can predict its
     * verdict without running it.
     */
    private static final Set<String> CREDENTIAL_WORDS = Set.of(
        "bf", "brute", "bruteforce", "auth", "login", "logon", "signin",
        "password", "passwd", "cred", "creds", "credentials");

    /** Whether a decision of this kind earns an email, as opposed to only a place in the Security view. */
    public boolean worthEmailing() {
        return this == CREDENTIAL_ATTACK;
    }

    /**
     * Classify one CrowdSec scenario name. The author namespace is stripped first — CrowdSec names
     * scenarios {@code author/scenario}, and only the scenario half describes what was caught. A null,
     * blank or unrecognised scenario is {@link #BLIND_SCANNING}, per the silent default documented above.
     */
    public static ThreatKind of(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return BLIND_SCANNING;
        }
        String name = scenario.substring(scenario.lastIndexOf('/') + 1).toLowerCase();
        boolean credentialed = Arrays.stream(name.split("[-_.]"))
            .anyMatch(CREDENTIAL_WORDS::contains);
        return credentialed ? CREDENTIAL_ATTACK : BLIND_SCANNING;
    }
}
