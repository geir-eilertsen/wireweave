package net.vaier.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every machine's {@link LastServiceReached} — at most one entry per machine, because only the latest is
 * the last one. Bounded by the size of the fleet, so nothing here needs a cap or a retention rule.
 *
 * <p>Immutable: every recording returns a new collection, which is what lets the store swap one reference
 * rather than lock a mutable list on the forward-auth path.
 */
public record LastServicesReached(List<LastServiceReached> entries) {

    public LastServicesReached {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static LastServicesReached of(List<LastServiceReached> entries) {
        return new LastServicesReached(entries);
    }

    public static LastServicesReached empty() {
        return new LastServicesReached(List.of());
    }

    /** What that machine last reached, if it has reached anything Vaier gates. */
    public Optional<LastServiceReached> forMachine(MachineId machineId) {
        if (machineId == null) return Optional.empty();
        return entries.stream().filter(entry -> entry.isFor(machineId)).findFirst();
    }

    /**
     * This collection with {@code reached} as that machine's latest, leaving every other machine alone.
     *
     * <p>An older reach is dropped rather than stored: requests arrive concurrently and the clocks between
     * hops are not identical, so a straggler that overwrote a newer entry would make a machine look like it
     * had gone back to a service it left.
     */
    public LastServicesReached with(LastServiceReached reached) {
        if (reached == null) return this;
        if (!reached.isNewerThan(forMachine(reached.machineId()).orElse(null))) return this;
        List<LastServiceReached> updated = new ArrayList<>(entries.size() + 1);
        for (LastServiceReached entry : entries) {
            if (!entry.isFor(reached.machineId())) updated.add(entry);
        }
        updated.add(reached);
        return new LastServicesReached(updated);
    }
}
