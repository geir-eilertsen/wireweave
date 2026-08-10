package net.fjordomatic.application;

import net.fjordomatic.domain.Transfer;

import java.util.List;

/**
 * Read the transfers Fjord knows about — those in flight, plus a capped tail of recently-settled ones — so
 * the browser can repaint the Clipboard's progress on load or after an SSE reconnect (#321, slice 2).
 * Transfers are ephemeral live operations held in memory; a Fjord restart simply loses them.
 */
public interface GetTransfersUseCase {

    /** Live and recently-settled transfers. */
    List<Transfer> getTransfers();
}
