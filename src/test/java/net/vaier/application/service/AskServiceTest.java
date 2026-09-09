package net.vaier.application.service;

import net.vaier.domain.AskPrompt;
import net.vaier.domain.AskTool;
import net.vaier.domain.AskUnavailableException;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ConversationTurn.Role;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AskServiceTest {

    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForConversing forConversing;

    @InjectMocks AskService service;

    private static final List<ToolOffer> TOOLS =
        List.of(new ToolOffer(AskTool.FLEET, () -> "colina27 connected"));

    private VaierConfig configuredWithAKey() {
        return VaierConfig.builder()
            .domain("example.com")
            .anthropicApiKey("sk-ant-api03-the-key")
            .build();
    }

    @Test
    void isAvailable_isTrueOnceAnAnthropicApiKeyIsStored() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_isFalseWithoutAKey() {
        when(configPersistence.load()).thenReturn(Optional.of(
            VaierConfig.builder().domain("example.com").build()));

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_isFalseOnAVaierThatHasNeverBeenConfigured() {
        when(configPersistence.load()).thenReturn(Optional.empty());

        assertThat(service.isAvailable()).isFalse();
    }

    /** The key never appears in the question, the prompt or the answer — it is handed to the port and nowhere else. */
    @Test
    void ask_handsTheStoredKeyThePromptAndTheToolsToThePort() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));
        List<ConversationTurn> history = List.of(new ConversationTurn(Role.OPERATOR, "hello"));
        Consumer<String> onText = text -> { };

        service.ask("which machine is red?", history, TOOLS, onText);

        verify(forConversing).converse(eq("sk-ant-api03-the-key"),
            eq(AskPrompt.forFleet("example.com").text()),
            eq(history), eq("which machine is red?"), eq(TOOLS), eq(onText));
    }

    /** The prompt is the domain's, built from the fleet's own base domain. */
    @Test
    void ask_buildsTheSystemPromptForThisFleet() {
        when(configPersistence.load()).thenReturn(Optional.of(configuredWithAKey()));

        service.ask("anything?", List.of(), TOOLS, text -> { });

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(forConversing).converse(anyString(), prompt.capture(), anyList(), anyString(), anyList(),
            any());
        assertThat(prompt.getValue()).contains("example.com");
        assertThat(prompt.getValue()).contains("Answer only from what the tools return.");
    }

    /**
     * Asked again after the operator cleared the key, Ask refuses rather than reaching for a null key —
     * availability is re-decided on every question, not remembered from when the pane was opened.
     */
    @Test
    void ask_refusesWhenNoAnthropicApiKeyIsStored() {
        when(configPersistence.load()).thenReturn(Optional.of(
            VaierConfig.builder().domain("example.com").build()));

        assertThatThrownBy(() -> service.ask("which machine is red?", List.of(), TOOLS, text -> { }))
            .isInstanceOf(AskUnavailableException.class)
            .hasMessageContaining("Add one in Settings");

        verify(forConversing, never()).converse(any(), any(), any(), any(), any(), any());
    }
}
