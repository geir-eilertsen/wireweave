package net.vaier.application;

import net.vaier.domain.BlockDecision;

import java.util.List;

/**
 * Reads the fleet's currently active {@link BlockDecision}s — who CrowdSec is keeping out right now
 * (#329 Slice 3b).
 *
 * <p>An empty list means CrowdSec was asked and is keeping nobody out. A failure to ask throws
 * {@link net.vaier.domain.BlockDecisionsUnreadableException}: this read feeds a screen where an empty list
 * reads as an all-clear, so it takes
 * {@link net.vaier.domain.port.ForDetectingIntrusions#getActiveDecisionsOrFail()} and never the sweep's
 * silent read.
 */
public interface GetBlockDecisionsUseCase {

    List<BlockDecision> getBlockDecisions();
}
