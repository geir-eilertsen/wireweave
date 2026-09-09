package net.vaier.application.service;

import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AskUseCase;
import net.vaier.application.IsAskAvailableUseCase;
import net.vaier.domain.AskAvailability;
import net.vaier.domain.AskPrompt;
import net.vaier.domain.ConversationTurn;
import net.vaier.domain.ToolOffer;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForConversing;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * <b>Ask</b> (#360 slice 1): the operator's questions about the fleet, answered from the fleet's own facts.
 *
 * <p>It orchestrates and decides nothing. Whether Ask may be offered is {@link AskAvailability}'s decision,
 * what Vaier tells the model is {@link AskPrompt}'s, which reads exist is {@link net.vaier.domain.AskTool}'s,
 * and holding the conversation is the {@link ForConversing} adapter's.
 */
@Service
@Slf4j
public class AskService implements AskUseCase, IsAskAvailableUseCase {

    private final ForPersistingAppConfiguration configPersistence;
    private final ForConversing forConversing;

    public AskService(ForPersistingAppConfiguration configPersistence, ForConversing forConversing) {
        this.configPersistence = configPersistence;
        this.forConversing = forConversing;
    }

    @Override
    public boolean isAvailable() {
        return AskAvailability.of(configPersistence.load()).available();
    }

    @Override
    public void ask(String question, List<ConversationTurn> history, List<ToolOffer> tools,
                    Consumer<String> onText) {
        // Re-decided on every question, never remembered from when the pane was opened: the operator may
        // have cleared the key since.
        Optional<VaierConfig> config = configPersistence.load();
        AskAvailability.of(config).requireAvailable();

        VaierConfig configured = config.orElseThrow();
        log.info("Ask: answering a question with {} tools offered", tools.size());
        forConversing.converse(configured.getAnthropicApiKey(),
            AskPrompt.forFleet(configured.getDomain()).text(),
            history, question, tools, onText);
    }
}
