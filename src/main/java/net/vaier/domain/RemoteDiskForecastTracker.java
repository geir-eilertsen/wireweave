package net.vaier.domain;

import net.vaier.domain.port.ForPersistingDiskFillTrends;

import java.time.Instant;
import java.util.Optional;

/**
 * Decides, per filesystem, whether admins should hear that it is heading for its alert threshold — the
 * trend-watching sibling of {@link RemoteDiskPressureTracker}, which watches the level. It reaches its own
 * state through
 * {@link ForPersistingDiskFillTrends}; the watcher hands the port in and then only decides <em>whom to
 * tell</em>. The runway it judges runs to that threshold, not to 100% — see {@link DiskFillForecast}.
 *
 * <p><b>The same latch bug, one line further down.</b> When the disk-pressure tracker's boolean latch was
 * moved onto disk, this one was left as a field on the scheduled watcher — so every redeploy still wiped
 * both the already-warned latch <em>and</em> the sample history it projects from. A trend needs days of
 * baseline and a redeploy happens several times a day here, so the forecast could not have fired even if the
 * arithmetic had been right. It was not: see {@link DiskFillTrend}.
 *
 * <p>Keyed on {@link MachineId} and mount point together, never the machine's name — {@code lan-servers.yml}
 * really does hold two machines both called "Printer", and a runway belongs to a filesystem, not a host.
 */
public class RemoteDiskForecastTracker {

    /** Whether this reading crossed into, out of, or neither, the early-warning condition. */
    public enum Transition { NONE, CROSSED_ABOVE, CROSSED_BELOW }

    private final ForPersistingDiskFillTrends trends;

    public RemoteDiskForecastTracker(ForPersistingDiskFillTrends trends) {
        this.trends = trends;
    }

    /**
     * Record a reading of {@code filesystem} on {@code machineId} and decide what admins should hear. All
     * the decisions — retention, slope, runway, the level-threshold gate, and crucially whether a cleared
     * crossing is a genuine recovery or a hand-off to the disk-pressure alert — live in the domain; the
     * watcher only sends whichever payload comes back.
     *
     * <p>{@code levelThreshold} is the threshold <em>this filesystem</em> is judged against — its own when
     * its {@link DiskWatch} carries one, otherwise the global disk alert threshold — already resolved by the
     * caller, so the forecast hands off to the pressure alert at exactly the level the pressure alert fires
     * at.
     *
     * <p>A crossing <em>into</em> the early-warning condition yields the {@link DiskFillForecast} to warn
     * with. A crossing <em>out</em> is split by why the gate flipped false:
     * <ul>
     *   <li><b>Genuine recovery</b> — the filesystem is still at/below {@code levelThreshold} (it drained, or
     *       its fill slowed so the runway rose back past the horizon) → a runway-free
     *       {@link DiskFillForecastCleared} all-clear.</li>
     *   <li><b>Hand-off</b> — it climbed past {@code levelThreshold} → nothing; the remote-disk-pressure alert
     *       now speaks for it, so raising an all-clear at the same poll would contradict it.</li>
     * </ul>
     */
    public synchronized Observation observe(MachineId machineId, RemoteDiskUsage filesystem, Instant at,
                                            int levelThreshold) {
        String mountPoint = filesystem.mountPoint();
        Optional<DiskFillTrend> stored = trends.find(machineId, mountPoint);
        DiskFillTrend trend = stored
            .orElseGet(() -> DiskFillTrend.startFor(machineId, mountPoint))
            .observing(at, filesystem.availableKb());

        Optional<DiskFillForecast> forecast = trend.forecast(filesystem, levelThreshold);
        boolean warrants = forecast
            .map(f -> f.warrantsEarlyWarning(levelThreshold, trend.warned()))
            .orElse(false);

        DiskFillTrend updated = trend.withWarned(warrants);
        // Written only when something actually moved, so a five-minute sweep that retains no new sample and
        // changes no latch touches no file.
        if (!stored.map(updated::equals).orElse(false)) {
            trends.save(updated);
        }

        Transition transition = Transition.NONE;
        Optional<DiskFillForecast> earlyWarning = Optional.empty();
        Optional<DiskFillForecastCleared> cleared = Optional.empty();
        if (warrants && !trend.warned()) {
            transition = Transition.CROSSED_ABOVE;
            earlyWarning = forecast;
        } else if (!warrants && trend.warned()) {
            transition = Transition.CROSSED_BELOW;
            if (filesystem.usedPercent() <= levelThreshold) {
                // Genuine recovery: drained, or fill slowed so the runway rose past the horizon.
                cleared = Optional.of(new DiskFillForecastCleared(filesystem.machineName(), mountPoint,
                    filesystem.usedPercent()));
            }
            // else: hand-off to the disk-pressure alert — suppress the forecast clear.
        }
        return new Observation(transition, earlyWarning, cleared);
    }

    /**
     * The outcome of an {@link #observe} call: the boundary crossing, plus the ready-to-send payloads —
     * an early warning on a crossing in, an all-clear on a genuine recovery out, both empty otherwise.
     */
    public record Observation(Transition transition,
                              Optional<DiskFillForecast> earlyWarning,
                              Optional<DiskFillForecastCleared> cleared) { }
}
