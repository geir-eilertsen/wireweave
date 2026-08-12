package net.vaier.domain;

import java.time.Instant;

/**
 * One retained free-space reading of a filesystem, in 1024-byte blocks.
 *
 * <p>Free <b>space</b>, never the {@code Use%} column: {@code df} prints an integer percent, and on a disk
 * filling at ~0.9%/day every reading inside a short window is the same integer, so a slope fitted through
 * them is exactly zero. Bytes have no such floor.
 *
 * @param at          when the reading was taken
 * @param availableKb capacity still free, in 1024-byte blocks
 */
public record DiskFillSample(Instant at, long availableKb) {

    public DiskFillSample {
        if (at == null) {
            throw new IllegalArgumentException("A disk-fill sample needs the time it was taken");
        }
        if (availableKb < 0) {
            throw new IllegalArgumentException("A disk-fill sample cannot have negative free space");
        }
    }
}
