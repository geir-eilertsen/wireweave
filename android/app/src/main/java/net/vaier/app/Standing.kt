package net.vaier.app

/** How Vaier answered when this phone asked whether it is still one of its devices. */
enum class Standing { MEMBER, REMOVED, UNREACHABLE }

/** What the phone does about that answer. */
data class StandingSteps(
    val forget: Boolean,
    val reconnect: Boolean,
    val notify: Boolean,
    val notice: String?,
)

/**
 * When a connected phone should ask Vaier whether it is still wanted, and what the answer costs it.
 *
 * <p>Nothing reaches a phone that has been removed: Vaier simply stops answering its handshakes, and
 * with everything routed through the tunnel the phone cannot even ask through it. So silence is the
 * only signal there is, and asking means taking the connection down first and going out over ordinary
 * internet — which is far too rude to do for a lift ride through a tunnel or a walk out of range.
 * Hence the two guards here: the connection must have been quiet for [SILENCE_MILLIS], and no matter
 * how quiet it goes the phone interrupts itself no more than once every [PATIENCE_MILLIS].
 */
object StandingWatch {

    /** How long a connection goes without a word before its silence means something. */
    const val SILENCE_MILLIS = 3 * 60_000L

    /** The least time between two interruptions, however bad the network is behaving. */
    const val PATIENCE_MILLIS = 5 * 60_000L

    const val NOTICE = "Vaier removed this phone. Join again to reconnect."
    const val NOTIFICATION_TITLE = "Removed from Vaier"

    fun worthAsking(
        now: Long,
        latestHandshakeEpochMillis: Long,
        connectedSinceEpochMillis: Long,
        lastAskedEpochMillis: Long,
    ): Boolean {
        if (now - lastAskedEpochMillis < PATIENCE_MILLIS) return false
        // A connection that has never been in touch is judged from when it came up, so a phone that
        // was already removed before it was switched on still finds out.
        val quietSince =
            if (latestHandshakeEpochMillis > 0) latestHandshakeEpochMillis else connectedSinceEpochMillis
        return now - quietSince > SILENCE_MILLIS
    }

    /** The answer to a connection that went quiet: whatever happens, put back what was taken down. */
    fun afterSilence(outcome: Standing): StandingSteps = when (outcome) {
        Standing.REMOVED -> StandingSteps(forget = true, reconnect = false, notify = true, notice = NOTICE)
        Standing.MEMBER, Standing.UNREACHABLE ->
            StandingSteps(forget = false, reconnect = true, notify = false, notice = null)
    }

    /**
     * The answer to opening the app with the connection off. Nothing was taken down, so nothing is put
     * back — and nobody is sent a notification about a screen they are already looking at.
     */
    fun afterOpening(outcome: Standing): StandingSteps = when (outcome) {
        Standing.REMOVED -> StandingSteps(forget = true, reconnect = false, notify = false, notice = NOTICE)
        Standing.MEMBER, Standing.UNREACHABLE ->
            StandingSteps(forget = false, reconnect = false, notify = false, notice = null)
    }

    fun removalText(deviceName: String): String =
        "${deviceName.trim().ifBlank { "This phone" }} was removed from Vaier by whoever runs it. " +
            "Open the app to join again."
}
