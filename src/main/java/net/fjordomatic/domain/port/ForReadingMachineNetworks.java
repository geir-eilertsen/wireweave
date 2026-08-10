package net.fjordomatic.domain.port;

import net.fjordomatic.domain.MachineId;
import net.fjordomatic.domain.MachineNetworks;

/**
 * Driven port for asking a machine which IPv4 networks it is on, and which one carries its default route
 * (#333). The counterpart to running {@code df} for a disk reading: same SSH channel, same credential,
 * a different question.
 *
 * <p>The adapter translates only — it resolves the machine to an SSH target, runs
 * {@link MachineNetworks#IP_COMMAND}, pins the host key on first use, and hands the output to the domain
 * parser. It evaluates no rule: which interfaces could be a LAN, and which network is <em>the</em> one
 * behind a machine, are decisions on {@link MachineNetworks}.
 */
public interface ForReadingMachineNetworks {

    /**
     * Read {@code machineId}'s networks now. Returns {@link MachineNetworks#unknown()} when the machine
     * answered with nothing Fjord can read — a command that failed, timed out, or produced output in no
     * shape it recognises. Transport and auth failures surface as the domain SSH exceptions, exactly as
     * they do for every other command path.
     */
    MachineNetworks read(MachineId machineId);
}
