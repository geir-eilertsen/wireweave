package net.vaier.adapter.driven;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.DeviceClaim;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachinePosition;
import net.vaier.domain.MachinePositions;
import net.vaier.domain.PositionTrail;
import net.vaier.domain.ReportedPosition;
import net.vaier.domain.port.ForPersistingMachinePositions;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File-backed store of {@link MachinePosition}s: one entry per machine in {@code machine-positions.yml},
 * under the root {@code positions:} key, keyed on the machine id.
 *
 * <p>The claim token is encrypted at rest via {@link SecretCipher} and the file is locked down like the
 * credential vault's. It is not a credential — it authorises exactly one thing, "record this machine's
 * position" — but it is still a bearer token, and nothing is gained by leaving it in the clear.
 *
 * <p>Written wholesale on every save, because forgetting a device must actually remove its entry; an
 * append-only store could never revoke a claim. An absent file is the healthy first-boot state.
 *
 * <p>Tolerant on load: an unreadable entry is skipped with a warning rather than aborting the load. The
 * cost of a skipped entry is one dot on the map; the cost of an aborted load is every dot.
 */
@Component
@Slf4j
public class MachinePositionFileAdapter implements ForPersistingMachinePositions {

    private static final String FILE_NAME = "machine-positions.yml";
    private final String filePath;
    private final SecretCipher cipher;

    public MachinePositionFileAdapter() {
        this(System.getenv().getOrDefault("VAIER_CONFIG_PATH", "/vaier/config"), new SecretCipher());
    }

    public MachinePositionFileAdapter(String configDir, SecretCipher cipher) {
        this.filePath = configDir + "/" + FILE_NAME;
        this.cipher = cipher;
    }

    @Override
    public synchronized MachinePositions getAll() {
        File file = new File(filePath);
        if (!file.exists()) return MachinePositions.empty();
        try (FileInputStream fis = new FileInputStream(file)) {
            Map<String, Object> data = new Yaml().load(fis);
            if (data == null) return MachinePositions.empty();
            if (!(data.get("positions") instanceof List<?> list)) return MachinePositions.empty();
            List<MachinePosition> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> m) {
                    MachinePosition position = deserialize(m);
                    if (position != null) result.add(position);
                }
            }
            return MachinePositions.of(result);
        } catch (Exception e) {
            log.warn("Failed to load machine positions from {}", filePath, e);
            return MachinePositions.empty();
        }
    }

    /**
     * Identity, merge and write all happen together under this monitor, so a report can only ever be
     * attributed by a claim that is genuinely still on disk — and combined with what is genuinely there.
     * Both decisions are the domain's, taken here only so that nothing can change between them.
     */
    @Override
    public synchronized Optional<MachineId> recordReportedPosition(MachineId fromTunnel, String claimToken,
                                                                   ReportedPosition position) {
        MachinePositions positions = getAll();
        Optional<MachineId> reporting = positions.reportingMachine(fromTunnel, claimToken);
        reporting.ifPresent(machineId -> writeAll(positions.withPositionFor(machineId, position)));
        return reporting;
    }

    @Override
    public synchronized void saveClaim(MachineId machineId, DeviceClaim claim) {
        writeAll(getAll().withClaimFor(machineId, claim));
    }

    @Override
    public synchronized void remove(MachineId machineId) {
        writeAll(getAll().without(machineId));
    }

    /**
     * One YAML entry into a {@link MachinePosition}, or null when it will not make one. What counts as a
     * usable position is the domain's call, not a rule re-derived here; all this does is map YAML to types
     * and skip whatever the domain refuses.
     */
    private MachinePosition deserialize(Map<?, ?> m) {
        try {
            MachineId machineId = MachineId.of(asString(m.get("machineId")));
            ReportedPosition position = m.get("latitude") == null ? null
                : ReportedPosition.report(asDouble(m.get("latitude")), asDouble(m.get("longitude")),
                    asDouble(m.get("accuracyMetres")), Instant.parse(asString(m.get("reportedAt"))));
            // Straight into the trail, never back through the domain's "does this earn a place" decision:
            // that was settled when the point was reported, and re-running it here would add a point per read.
            return new MachinePosition(machineId, position, deserializeClaim(m), deserializeTrail(m));
        } catch (Exception e) {
            log.warn("Skipping unreadable entry in {}: {}", FILE_NAME, e.toString());
            return null;
        }
    }

    /**
     * The stored trail, skipping any point that will not read. Deliberately not encrypted, unlike the
     * claim: a lost or rotated vault key already costs the claim, and it must not also silently cost the
     * operator a month of their own history — which is exactly what a whole-entry cipher would do.
     */
    private PositionTrail deserializeTrail(Map<?, ?> m) {
        if (!(m.get("trail") instanceof List<?> stored)) return PositionTrail.empty();
        List<ReportedPosition> points = new ArrayList<>();
        for (Object entry : stored) {
            if (!(entry instanceof Map<?, ?> point)) continue;
            try {
                points.add(ReportedPosition.report(asDouble(point.get("latitude")),
                    asDouble(point.get("longitude")), asDouble(point.get("accuracyMetres")),
                    Instant.parse(asString(point.get("at")))));
            } catch (Exception e) {
                log.warn("Skipping an unreadable trail point in {}: {}", FILE_NAME, e.toString());
            }
        }
        return new PositionTrail(points);
    }

    /**
     * The claim, or null when it will not read — a rotated or lost vault key makes the token
     * undecryptable. Scoped apart from the position on purpose: a broken token costs the claim, never
     * the dot on the map, which is exactly the tolerance this adapter exists to provide.
     */
    private DeviceClaim deserializeClaim(Map<?, ?> m) {
        try {
            String token = cipher.decrypt(asString(m.get("claimToken")));
            return token == null ? null : new DeviceClaim(token, Instant.parse(asString(m.get("claimedAt"))));
        } catch (Exception e) {
            log.warn("Dropping an unreadable device claim in {}: {}", FILE_NAME, e.toString());
            return null;
        }
    }

    private void writeAll(MachinePositions positions) {
        File file = new File(filePath);
        File parentDir = file.getParentFile();
        if (parentDir != null && !parentDir.exists()) parentDir.mkdirs();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        Yaml yaml = new Yaml(options);

        List<Map<String, Object>> serialized = new ArrayList<>();
        for (MachinePosition machinePosition : positions.entries()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("machineId", machinePosition.machineId().value());
            ReportedPosition position = machinePosition.position();
            if (position != null) {
                entry.put("latitude", position.latitude());
                entry.put("longitude", position.longitude());
                if (position.accuracyMetres() != null) {
                    entry.put("accuracyMetres", position.accuracyMetres());
                }
                entry.put("reportedAt", position.reportedAt().toString());
            }
            List<Map<String, Object>> trail = serializeTrail(machinePosition.trail());
            if (!trail.isEmpty()) entry.put("trail", trail);
            DeviceClaim claim = machinePosition.claim();
            if (claim != null) {
                entry.put("claimToken", cipher.encrypt(claim.token()));
                entry.put("claimedAt", claim.claimedAt().toString());
            }
            serialized.add(entry);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("positions", serialized);

        try (FileWriter writer = new FileWriter(file)) {
            yaml.dump(root, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save machine positions to " + filePath, e);
        }
        SecureFilePermissions.lockDownFile(file.toPath());
    }

    private static List<Map<String, Object>> serializeTrail(PositionTrail trail) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (ReportedPosition point : trail.points()) {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("latitude", point.latitude());
            stored.put("longitude", point.longitude());
            if (point.accuracyMetres() != null) stored.put("accuracyMetres", point.accuracyMetres());
            stored.put("at", point.reportedAt().toString());
            points.add(stored);
        }
        return points;
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
