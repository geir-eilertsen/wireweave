package net.vaier.domain;

/**
 * What a <b>Read-only command</b> came back with, shaped for a reader rather than a caller: the exit code,
 * whether it was abandoned at the deadline, everything it printed on either stream, and whether that was
 * cut. It is cut before it reaches the model, and the model is told, so a chatty command is never a
 * surprise bill and never a silent half-answer.
 */
public record CommandOutcome(int exitCode, boolean timedOut, String output, boolean cut) {

    /** Enough for any listing that answers a question; a log wants {@code tail}, not a bigger cap. */
    public static final int MAX_CHARS = 12_000;

    public static CommandOutcome of(CommandResult result) {
        String out = result.stdout() == null ? "" : result.stdout();
        String err = result.stderr();
        if (err != null && !err.isBlank()) {
            out = out.isBlank() ? err : out + "\n" + err;
        }
        boolean cut = out.length() > MAX_CHARS;
        return new CommandOutcome(result.exitCode(), result.timedOut(), cut ? out.substring(0, MAX_CHARS) : out, cut);
    }
}
