package net.vaier.domain;

import net.vaier.domain.port.ForRecordingDockerCommandAccess;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether Vaier's SSH user can drive Docker on a machine — a fact about the <b>machine</b>, not about any
 * container on it.
 *
 * <p><b>Why Vaier cannot infer it from anything it already knows.</b> The container scrape reads Docker's
 * API over the tunnel and needs no Unix group at all, so a machine whose SSH user is not in its
 * {@code docker} group scrapes perfectly and looks entirely healthy — while every command Vaier would run
 * over SSH to <b>update</b> one of those containers dies on {@code permission denied while trying to
 * connect to the Docker API}. Colina 27 offered Update on all five of its containers and every one was
 * doomed. Catching that early is the point: a good error message is the backstop, never the plan.
 *
 * <p><b>Learned on a trip Vaier already makes.</b> The five-minute disk sweep signs in to every credentialed
 * machine to run {@code df}; this probe rides in front of that command on the same connection, so knowing
 * costs no extra sign-in per machine. It is deliberately non-mutating — it asks Docker its version and
 * discards everything but the exit status.
 *
 * <p><b>Held, never persisted</b>, and re-read on every sweep: it is what Vaier last saw, and a machine
 * whose operator has just fixed the group heals on the next pass without anything to reset.
 */
public enum DockerCommandAccess {

    /** The probe ran and Docker answered: Vaier can drive Docker on this machine as its SSH user. */
    GRANTED,

    /** The probe ran and Docker did not answer it — the SSH user cannot reach the daemon. */
    REFUSED,

    /**
     * Nobody has looked yet, or the last trip came back without an answer. <b>Not a no.</b> Vaier holds no
     * evidence either way, and treating that as a refusal would withhold the action from the whole fleet
     * for the minutes after every restart.
     */
    UNKNOWN;

    /** The marker the probe prints its exit status on — nothing else in the sweep's output looks like it. */
    private static final String MARKER = "VAIER-DOCKER-RC=";

    private static final Pattern MARKER_LINE = Pattern.compile("^" + MARKER + "(\\d+)$", Pattern.MULTILINE);

    /**
     * {@code command} with the Docker probe run ahead of it, as one command for one connection.
     *
     * <p><b>Ahead of, and silent.</b> The probe's own stdout and stderr are discarded and its exit status is
     * printed on a marker line, so {@code command} still writes the last word on both streams and still
     * supplies the exit status the caller judges it by. A probe appended <em>after</em> would replace that
     * exit status with its own, and a probe that spoke on stderr would put a permission error in front of a
     * disk reading every five minutes. The marker line cannot parse as a {@code df} row, so the reading it
     * rides with is unchanged.
     */
    public static String probeAheadOf(String command) {
        return "docker version --format '{{.Server.Version}}' >/dev/null 2>&1; echo " + MARKER + "$?; " + command;
    }

    /** What the sweep's output says about Docker — {@link #UNKNOWN} when it carries no marker at all. */
    public static DockerCommandAccess readFrom(CommandResult result) {
        if (result == null || result.stdout() == null) {
            return UNKNOWN;
        }
        Matcher marker = MARKER_LINE.matcher(result.stdout().strip());
        if (!marker.find()) {
            return UNKNOWN;
        }
        return "0".equals(marker.group(1)) ? GRANTED : REFUSED;
    }

    /** Whether Vaier knows it cannot drive Docker here. Only a verdict actually taken says yes. */
    public boolean refused() {
        return this == REFUSED;
    }

    /**
     * Keep what this sweep learned about {@code machineId} — and only when it learned something.
     *
     * <p>A trip that came back without the marker is not evidence that Docker is out of reach; it is a
     * machine that was asleep, or a command that never ran. Recording {@link #UNKNOWN} for it would erase a
     * fact Vaier holds and re-offer an action already known to be doomed. Same shape as
     * {@code MachineDiskStanding.retain}: the port is handed in and called here, so the rule about what is
     * worth recording lives with the reading rather than in the sweep.
     */
    public static void retain(MachineId machineId, CommandResult result,
                              ForRecordingDockerCommandAccess recorder) {
        DockerCommandAccess access = readFrom(result);
        if (access != UNKNOWN) {
            recorder.record(machineId, access);
        }
    }
}
