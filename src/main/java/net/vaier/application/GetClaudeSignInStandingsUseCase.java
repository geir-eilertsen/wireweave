package net.vaier.application;

import net.vaier.domain.ClaudeSignInStatus;

import java.util.List;

/**
 * Read the whole fleet's <b>Claude sign-in standing</b>s in one go — what the Explorer's fleet listing
 * marks each machine card with.
 *
 * <p><b>It wakes nothing</b>, and that is the whole difference between this and
 * {@link GetClaudeSignInStatusUseCase}. That one SSHes to a machine and asks the CLI, because it paints
 * one machine's own pane; asking it once per card would put a fleet-wide SSH sweep behind opening the
 * Explorer, with every sleeping box waited on before anything appeared. This is a read of what
 * {@code RemoteDiskWatcher}'s five-minute trip already found, held in memory.
 *
 * <p>A machine the sweep has not reached simply has no standing, and a card must then draw no mark at
 * all. That matters more here than it does for disks: the read side goes to real trouble never to report
 * a sign-in state the CLI did not actually say, and a mark invented from absence would undo it.
 */
public interface GetClaudeSignInStandingsUseCase {

    /** Every standing Vaier currently holds — never one per machine, only per machine it has read. */
    List<ClaudeSignInStatus> getClaudeSignInStandings();
}
