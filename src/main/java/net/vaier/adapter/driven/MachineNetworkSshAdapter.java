package net.vaier.adapter.driven;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.CommandResult;
import net.vaier.domain.MachineId;
import net.vaier.domain.MachineNetworks;
import net.vaier.domain.SshTarget;
import net.vaier.domain.port.ForReadingMachineNetworks;
import net.vaier.domain.port.ForResolvingSshTargets;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;
import org.springframework.stereotype.Component;

/**
 * Asks a machine which IPv4 networks it is on (#333), over the same exec channel every other remote
 * command goes through. A capability adapter in the shape of {@link SurvivalKitKeeperAdapter}: it
 * composes the lower-level SSH ports rather than making a service drive them, so the only thing a
 * consumer needs is "read this machine's networks".
 *
 * <p>Translation only. The command is {@link MachineNetworks#IP_COMMAND} — the domain's, not this
 * class's — and the output goes straight to {@link MachineNetworks#parse}, which owns every judgement
 * about what it means. A run that failed or timed out yields {@link MachineNetworks#unknown()}: a machine
 * Vaier could not read is not a machine with no networks, and the difference matters, because the second
 * would be offered as evidence.
 *
 * <p>It pins the host key on first use like every other SSH-reaching path. A machine that Vaier only ever
 * touched here would otherwise never gain a pinned key, and so could never detect one changing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MachineNetworkSshAdapter implements ForReadingMachineNetworks {

    private final ForResolvingSshTargets targets;
    private final ForRunningSshCommands ssh;
    private final ForTrackingHostKeys hostKeys;

    @Override
    public MachineNetworks read(MachineId machineId) {
        SshTarget target = targets.resolve(machineId);
        CommandResult result = ssh.run(target, MachineNetworks.IP_COMMAND);
        target.pinOnFirstUse(result.hostKeyFingerprint(), hostKeys);

        if (result.timedOut() || result.exitCode() != 0) {
            log.debug("Reading networks on machine {} failed (exit={}, timedOut={})", machineId,
                result.exitCode(), result.timedOut());
            return MachineNetworks.unknown();
        }
        return MachineNetworks.parse(result.stdout());
    }
}
