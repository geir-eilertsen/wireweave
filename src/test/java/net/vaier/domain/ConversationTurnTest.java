package net.vaier.domain;

import net.vaier.domain.ConversationTurn.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** One turn of a <b>Conversation</b>: who spoke, and what they said (#360). */
class ConversationTurnTest {

    @Test
    void aTurnIsSpokenByTheOperatorOrByVaier() {
        assertThat(new ConversationTurn(Role.OPERATOR, "which machine is red?").role())
            .isEqualTo(Role.OPERATOR);
        assertThat(Role.values()).containsExactly(Role.OPERATOR, Role.VAIER);
    }

    @Test
    void aTurnMustSayWhoSpoke() {
        assertThatThrownBy(() -> new ConversationTurn(null, "hello"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** An empty turn is not a turn; it would reach the model as a blank message and be refused there. */
    @Test
    void aTurnMustCarryWords() {
        assertThatThrownBy(() -> new ConversationTurn(Role.VAIER, "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
