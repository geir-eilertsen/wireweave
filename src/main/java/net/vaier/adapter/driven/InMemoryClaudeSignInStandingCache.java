package net.vaier.adapter.driven;

import net.vaier.domain.ClaudeSignInStatus;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForHoldingClaudeSignInStandings;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory home for the fleet's <b>Claude sign-in standing</b>s — the sibling of
 * {@link InMemoryMachineDiskStandingCache}, and written on the very same 5-minute sweep:
 * {@code RemoteDiskWatcher} records what the CLI on each machine already told it, and the Explorer's fleet
 * listing reads the whole fleet's standings back in one memory-backed request that wakes nothing.
 *
 * <p>It holds no credential material and could not: a standing is read from {@code claude auth status
 * --json}, which emits no token, key or session at all.
 *
 * <p>Keyed by {@link MachineId}, never by name, so a rename never loses or crosses a machine's sign-in.
 */
@Component
public class InMemoryClaudeSignInStandingCache implements ForHoldingClaudeSignInStandings {

    private final Map<MachineId, ClaudeSignInStatus> standings = new ConcurrentHashMap<>();

    @Override
    public Optional<ClaudeSignInStatus> record(ClaudeSignInStatus standing) {
        return Optional.ofNullable(standings.put(standing.machineId(), standing));
    }

    @Override
    public List<ClaudeSignInStatus> getAll() {
        return List.copyOf(standings.values());
    }

    @Override
    public void retainOnly(Set<MachineId> machineIds) {
        standings.keySet().retainAll(machineIds);
    }
}
