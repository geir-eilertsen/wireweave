package net.vaier.domain;

/**
 * One turn of a <b>Conversation</b>: who spoke, and what they said (#360). The browser holds the
 * conversation for the visit and sends it back with each question, so this is what a turn looks like on the
 * way in — Vaier keeps none of it yet.
 */
public record ConversationTurn(Role role, String text) {

    /** Who spoke. Only two things ever do. */
    public enum Role { OPERATOR, VAIER }

    public ConversationTurn {
        if (role == null) {
            throw new IllegalArgumentException("A conversation turn must say who spoke");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A conversation turn must carry words");
        }
    }
}
