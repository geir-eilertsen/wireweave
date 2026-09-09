package net.vaier.application;

/**
 * Whether <b>Ask</b> is offered at all (#360). Asked by the Explorer so the pane appears only when there is
 * something to ask with — an <b>Anthropic API key</b> is the whole of it.
 */
public interface IsAskAvailableUseCase {

    boolean isAvailable();
}
