package net.vaier.domain;

import net.vaier.domain.port.ForPublishingEvents;
import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForStoringContainerSnapshots;
import net.vaier.domain.port.ForTrackingHostKeys;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * One container's <b>update</b>: recreating it on the newer image its registry now serves, from the
 * {@link ComposeCoordinates compose coordinates} it carries about itself. Everything the act decides lives
 * here — whether it may happen at all, what compose is asked to do, how long each run may take, and how the
 * result reads.
 *
 * <p><b>Two runs, not one.</b> {@code pull} and {@code up -d} are issued separately so the outcome can tell
 * a failed pull from a failed recreate. A recreate that fails leaves the old container running, which is the
 * good outcome of a bad update, and Vaier says so instead of reporting a generic error.
 *
 * <p><b>Over SSH, never through the Docker API.</b> Recreating a container through the daemon would mean
 * granting create and remove on the socket proxy whose whole job is to be narrow, and rebuilding a container
 * from its {@code inspect} output means re-deriving networks, volumes, env, labels, restart policy and
 * capabilities by hand — where anything missed comes back as a subtly different container that reports
 * success. Compose recreates its own service faithfully.
 *
 * <p><b>Every interpolated value is single-quoted.</b> The coordinates come from container labels, which are
 * metadata a container writes about itself; {@link ComposeCoordinates} refuses anything a shell reads as
 * syntax, and this builds only quoted words on top of that. A value that still carries a single quote — which
 * can only mean coordinates built without that validation — is refused outright rather than emitted.
 */
public record ContainerUpdate(MachineId machineId, String containerName, String image,
                              ComposeCoordinates coordinates) {

    /**
     * How long a {@code compose pull} may take. Minutes, not seconds: an image is hundreds of megabytes and
     * the fleet's peers are on home connections. The 20-second default every other remote command runs under
     * would abandon an ordinary pull mid-flight and report a timeout for a working update.
     */
    public static final Duration PULL_TIMEOUT = Duration.ofMinutes(15);

    /** How long a {@code compose up -d} may take — a stop, a create and a start, not a download. */
    public static final Duration RECREATE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Where a settled update is announced: the fleet stream the Explorer already holds open for peer
     * liveness and disk standings. A second connection for one event would be a second thing to keep alive.
     */
    private static final String SSE_TOPIC = "vpn-peers";
    private static final String SSE_EVENT = "container-update-settled";

    /**
     * The update of the container named {@code containerName} among {@code containersOnMachine} — the
     * containers scraped from the machine {@code machineId}.
     *
     * @throws NotFoundException when that machine has no container of that name
     * @throws ConflictException when it has one Vaier will not update, carrying the plain reason
     */
    public static ContainerUpdate of(MachineId machineId, String containerName,
                                     List<DockerService> containersOnMachine) {
        // A request with no container in it is unreadable, not a container that is missing: the two answers
        // send an operator looking in different places.
        if (containerName == null || containerName.isBlank()) {
            throw new IllegalArgumentException("An update must name the container to update");
        }
        DockerService container = containersOnMachine.stream()
            .filter(c -> containerName.equals(c.containerName()))
            .findFirst()
            .orElseThrow(() -> new NotFoundException(
                "No container named " + containerName + " on that machine"));

        ContainerUpdateEligibility verdict = verdictFor(container);
        if (!verdict.updatable()) {
            throw new ConflictException(verdict.refusal(containerName));
        }
        // The image is taken from the container the scrape reported, never re-derived from anywhere else:
        // it is the key half of the ScopedImage whose verdict an update retires, and a re-derived one
        // that spelled the tag differently would retire nothing while looking as if it had.
        return new ContainerUpdate(machineId, containerName, container.image(),
            container.composeCoordinates());
    }

    /**
     * The verdict this container carries, read conservatively. A container nobody has judged reads as
     * refused — <b>no verdict is never permission</b> — and so does one judged updatable that turns out to
     * carry no coordinates, because there is then nothing to recreate it from. Both come out as
     * {@link ContainerUpdateEligibility#NOT_COMPOSE_MANAGED}: Vaier holds no compose coordinates for the container,
     * which is exactly what that verdict says.
     */
    private static ContainerUpdateEligibility verdictFor(DockerService container) {
        ContainerUpdateEligibility verdict = container.updateEligibility();
        if (verdict == null || (verdict.updatable() && container.composeCoordinates() == null)) {
            return ContainerUpdateEligibility.NOT_COMPOSE_MANAGED;
        }
        return verdict;
    }

    /** {@code docker compose … pull <service>} — fetch the newer image without touching what runs. */
    public String pullCommand() {
        return composeCommand("pull");
    }

    /** {@code docker compose … up -d <service>} — recreate the service on the image just pulled. */
    public String recreateCommand() {
        return composeCommand("up -d");
    }

    /**
     * The compose invocation for {@code verb}, addressed by the container's own coordinates.
     *
     * <p><b>Every {@code -f} file appears, in the order compose recorded them.</b> A multi-file project
     * recreated from its first file alone silently drops whatever its override files said — which comes back
     * as a container that is subtly different and reports success. {@code --project-directory} is omitted
     * entirely when the container reports no working directory, rather than guessed at.
     */
    private String composeCommand(String verb) {
        StringBuilder command = new StringBuilder("docker compose");
        if (coordinates.workingDir() != null) {
            command.append(" --project-directory ").append(quoted(coordinates.workingDir()));
        }
        command.append(" -p ").append(quoted(coordinates.project()));
        coordinates.configFiles().forEach(file -> command.append(" -f ").append(quoted(file)));
        return command.append(' ').append(verb).append(' ').append(quoted(coordinates.service())).toString();
    }

    /**
     * {@code value} as one single-quoted shell word. A value containing a single quote would end that word
     * and begin something else, so it is refused rather than emitted — the last line of defence behind
     * {@link ComposeCoordinates}'s own validation, for coordinates that reached here without passing it.
     */
    private static String quoted(String value) {
        if (value.indexOf('\'') >= 0) {
            throw new IllegalArgumentException(
                "A compose coordinate carrying a single quote cannot be put on a command line");
        }
        return "'" + value + "'";
    }

    /**
     * Carry the update out on {@code target} and rule how it ended — <b>always</b>, including when the
     * attempt itself failed. An SSH port that throws is a way for an update to end like any other, so it
     * is read here into an {@link ContainerUpdateOutcome#UNREACHABLE} settlement rather than escaping to whoever
     * orchestrated the run and being classified there. This is the only way in: nothing a caller can reach
     * throws, so an accepted update cannot fail to settle.
     *
     * <p>{@code Exception} is caught deliberately widely. The anticipated failures are the domain SSH
     * exceptions, but the guarantee this method makes is about the ones nobody anticipated: a settled event
     * that never arrives leaves the Explorer waiting forever, and a bug is exactly the case that would
     * produce one.
     *
     * <p>The failure's own words leave with the settlement rather than being logged here — one class in
     * this whole domain logs, and a rule with a single exception is not a rule to widen for a warning.
     */
    public Settlement carryOut(SshTarget target, ForRunningSshCommands ssh, ForTrackingHostKeys hostKeys) {
        try {
            return run(target, ssh, hostKeys);
        } catch (Exception e) {
            return Settlement.failedAttempt(e);
        }
    }

    /**
     * How an update ended once it was carried out: its {@link ContainerUpdateOutcome}, and — whenever it did not
     * end well — <b>the host's own words about why</b>, kept so the reason reaches both the operator and
     * Vaier's log without the domain doing the logging.
     *
     * <p>Carrying the words was not the original rule. A non-zero exit was ruled "an answer, not a failed
     * attempt" and given no diagnostic at all, which read as principled and was wrong in practice: compose
     * writes its reason to stderr and Vaier captured it and threw it away, so a real failed update said
     * {@code PULL_FAILED} and nothing else, in the browser and in the log alike. The operator could not tell
     * a docker-group problem from an unreadable compose file from a registry they cannot reach. <b>Compose's
     * stderr is the answer</b>, and #352 asks for the truth on failure.
     *
     * @param outcome    what the operator is told, and what the settled event carries
     * @param diagnostic why it ended that way, in the host's words — or null when there is nothing to
     *                   explain (an update that worked) or nothing was said (a command that failed
     *                   silently). Already reduced to one bounded line: see {@link #summarise}.
     */
    public record Settlement(ContainerUpdateOutcome outcome, String diagnostic) {

        /**
         * How much of the host's words the operator is shown. A toast is not a log viewer, and the captured
         * streams are capped at a mebibyte each — the error itself is one line, and everything above it is
         * compose's progress chatter.
         */
        public static final int MAX_DIAGNOSTIC = 240;

        /** How a finished command run settles: its outcome, plus its own words when it did not go well. */
        static Settlement of(ContainerUpdateOutcome outcome, CommandResult result) {
            return new Settlement(outcome, outcome.updated() ? null : summarise(result));
        }

        /** An attempt that threw, read as unreachable and carrying what threw, named. */
        static Settlement failedAttempt(Exception failure) {
            String message = failure.getMessage();
            return new Settlement(ContainerUpdateOutcome.UNREACHABLE, failure.getClass().getSimpleName()
                + (message == null ? "" : ": " + message));
        }

        /**
         * What the operator is told: the outcome's own sentence, and — when there is one — the reason after
         * it.
         *
         * <p>The reason is <em>added to</em> the sentence and never replaces it. "The old container is still
         * running on the image it had" is the half that tells an operator whether their service is down, and
         * a reason that displaced it would trade one silence for another.
         */
        public String sentenceFor(String containerName) {
            String sentence = outcome.sentence(containerName);
            return diagnostic == null ? sentence : sentence + " The host said: " + diagnostic;
        }

        /**
         * A failed command reduced to the part that carries meaning: the <b>last non-blank line</b> — which
         * is where compose puts its error, under however much progress chatter — as one clean, bounded line.
         *
         * <p>Bounded and reduced for three reasons at once. A toast cannot hold a mebibyte; compose output
         * can echo env and path detail from someone's host, so a summary is taken rather than the stream;
         * and the settled event is hand-rolled JSON delivered over SSE, where a raw newline breaks the
         * framing <em>and</em> the parse, leaving the browser with nothing at all. Control characters
         * (compose's ANSI colour codes among them) are dropped and runs of whitespace collapsed, so what
         * comes out is always a single printable line.
         *
         * <p>Null when the command said nothing — an empty reason is worse than none, because it reads as
         * though Vaier is quoting a host that stayed silent.
         */
        private static String summarise(CommandResult result) {
            String spoken = lastMeaningfulLine(result.stderr());
            if (spoken == null) {
                spoken = lastMeaningfulLine(result.stdout());
            }
            if (spoken == null) {
                return null;
            }
            return spoken.length() <= MAX_DIAGNOSTIC
                ? spoken
                : spoken.substring(0, MAX_DIAGNOSTIC - 1) + "…";
        }

        /** The last line of {@code output} that says anything, cleaned — or null when none does. */
        private static String lastMeaningfulLine(String output) {
            if (output == null) {
                return null;
            }
            String[] lines = output.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String cleaned = printableOneLine(lines[i]);
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
            return null;
        }

        /** One line with every control character dropped and each run of whitespace collapsed to a space. */
        private static String printableOneLine(String line) {
            return line.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * The update proper: pull, then — only if the pull succeeded — recreate. The ports are handed in and
     * called here, so the ordering, the deadlines and the reading of each result stay in one place rather
     * than being re-decided by whoever orchestrates the run.
     *
     * <p>Private on purpose: {@link #carryOut} is the only entry point, so no caller can end up holding an
     * exception it has to classify for itself.
     *
     * <p>The host key is pinned on first use from the pull's result, exactly as every other path that reaches
     * a machine over SSH does: a machine only ever updated from would otherwise never gain a pinned key, and
     * could never have one detected changing.
     */
    private Settlement run(SshTarget target, ForRunningSshCommands ssh, ForTrackingHostKeys hostKeys) {
        CommandResult pull = ssh.run(target, pullCommand(), PULL_TIMEOUT);
        target.pinOnFirstUse(pull.hostKeyFingerprint(), hostKeys);

        Optional<ContainerUpdateOutcome> pullFailure = ContainerUpdateOutcome.ofPull(pull);
        if (pullFailure.isPresent()) {
            return Settlement.of(pullFailure.get(), pull);
        }
        CommandResult recreate = ssh.run(target, recreateCommand(), RECREATE_TIMEOUT);
        return Settlement.of(ContainerUpdateOutcome.ofRecreate(recreate), recreate);
    }

    /**
     * Retire what the last <b>update sweep</b> remembered about this container's image — but only when the
     * update actually happened.
     *
     * <p>The sweep files its verdict under a {@link ScopedImage}: the machine, and the image <em>tag</em>.
     * An update recreates the container on a new digest and leaves the tag exactly as it was, so nothing
     * about the remembered verdict changes on its own and the mark outlives the update that resolved it —
     * indefinitely, since re-scraping re-reads the same tag and re-applies the same remembered answer.
     *
     * <p><b>Forgotten, never stamped up to date.</b> Vaier pulled and recreated; it did not ask the registry
     * again and compare it against the new container's digest. Forgetting leaves the image reading
     * {@link UpdateAvailability#UNKNOWN} — "no sweep has judged this" — which is exactly what happened.
     * Claiming up to date would be asserting a verdict Vaier never took.
     *
     * <p><b>Only an update forgets.</b> After a failed pull, a failed recreate, a timeout or an unreachable
     * host the container is still running the image it had, so the mark is still true; clearing it would be
     * the same lie pointing the other way, and the operator would stop being told about work still to do.
     */
    public void forgetOutdatedVerdict(ContainerUpdateOutcome outcome, ForStoringContainerSnapshots snapshots) {
        if (outcome.updated()) {
            snapshots.forgetImageUpdateVerdict(new ScopedImage(machineId.value(), image));
        }
    }

    /**
     * Announce the settled update on the fleet stream: which machine, which container, how it ended, and
     * the sentence to show. The browser is handed the words rather than a code to invent words from, so the
     * "the old container is still running" reassurance cannot be lost in translation.
     */
    public void announce(Settlement settlement, ForPublishingEvents events) {
        events.publish(SSE_TOPIC, SSE_EVENT, "{"
            + "\"machineId\":\"" + jsonEscaped(machineId.value()) + "\","
            + "\"containerName\":\"" + jsonEscaped(containerName) + "\","
            + "\"outcome\":\"" + settlement.outcome() + "\","
            + "\"message\":\"" + jsonEscaped(settlement.sentenceFor(containerName)) + "\"}");
    }

    /**
     * Escape a value for embedding in a double-quoted JSON string.
     *
     * <p>Control characters are escaped, not only the backslash and double quote it used to handle. This
     * payload now carries a <b>host's own words</b>, and it is hand-rolled JSON delivered over SSE: the
     * emitter splits a payload on newlines into separate {@code data:} lines and the browser parses the
     * result inside a try/catch, so one raw newline costs the operator the whole message rather than one
     * line of it. The diagnostic is already reduced to a single printable line — this is the second lock
     * on the same door, and it covers the container name too, which Vaier does not choose either.
     */
    private static String jsonEscaped(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
