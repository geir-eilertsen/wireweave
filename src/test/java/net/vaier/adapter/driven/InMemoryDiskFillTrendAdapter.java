package net.vaier.adapter.driven;

import net.vaier.domain.DiskFillTrend;
import net.vaier.domain.MachineId;
import net.vaier.domain.port.ForPersistingDiskFillTrends;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Test-only in-memory stand-in for {@link ForPersistingDiskFillTrends}. Real deployments use
 * {@link DiskFillTrendFileAdapter}; tests that only care about the <em>decisions</em> the domain makes from
 * the stored trend use this so they never touch a temp directory. It survives a "redeploy" — a freshly
 * constructed tracker over the same instance — which is exactly what the persistence is for.
 */
public class InMemoryDiskFillTrendAdapter implements ForPersistingDiskFillTrends {

    private final Map<String, DiskFillTrend> trends = new LinkedHashMap<>();

    /** How many times {@link #save} has been called — the write rate the downsampling exists to hold down. */
    private int saves;

    @Override
    public synchronized Optional<DiskFillTrend> find(MachineId machineId, String mountPoint) {
        return Optional.ofNullable(trends.get(key(machineId, mountPoint)));
    }

    @Override
    public synchronized void save(DiskFillTrend trend) {
        saves++;
        trends.put(key(trend.machineId(), trend.mountPoint()), trend);
    }

    @Override
    public synchronized void retainOnly(Set<MachineId> machineIds) {
        trends.values().removeIf(trend -> machineIds.stream().noneMatch(trend.machineId()::isSameAs));
    }

    public synchronized int saves() {
        return saves;
    }

    public synchronized int size() {
        return trends.size();
    }

    /** Machine and mount joined on NUL — the one byte neither can contain, so no two keys collide. */
    private static String key(MachineId machineId, String mountPoint) {
        return machineId.value() + '\0' + mountPoint;
    }
}
