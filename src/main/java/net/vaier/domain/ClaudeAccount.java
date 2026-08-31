package net.vaier.domain;

/**
 * The Anthropic account a machine's Claude CLI is signed in as: the {@code email} it belongs to, the
 * {@code organisation} it sits in, and its {@code subscriptionType} (e.g. {@code max}, {@code pro}).
 * Any of the three may be null — the CLI reports what it knows, and an older one may report less.
 *
 * <p><b>Display only, and never stored.</b> It exists because signing a fleet into the <em>wrong</em>
 * account is otherwise invisible until something fails at the far end of a workflow: every machine looks
 * green, and the first symptom is a quota or a permission that makes no sense. Showing the account beside
 * the standing turns that into something an operator can see at a glance.
 *
 * <p>It carries no credential material, and could not: it is read from {@code claude auth status --json},
 * whose output contains no token, key or session of any kind. That is precisely why Vaier asks the CLI
 * this question instead of inferring the answer from the credential file the CLI keeps — the question has
 * an answer Vaier is allowed to hear.
 */
public record ClaudeAccount(String email, String organisation, String subscriptionType) {
}
