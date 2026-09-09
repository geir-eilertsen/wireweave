package net.vaier.app

/** How Vaier answered a request to leave. Removed covers "already gone", which is the same here. */
enum class LeaveOutcome { REMOVED, UNREACHABLE }

/** What the phone does about that answer. */
data class LeaveSteps(val forget: Boolean, val reconnect: Boolean, val notice: String?)

/**
 * The order leaving happens in, and what a failure owes the phone afterwards.
 *
 * <p>The connection goes down <b>before</b> the request is sent, never after: Vaier removes the peer
 * before it writes its answer, so a reply coming back through that very tunnel would never arrive —
 * and the phone would be told it is still in Vaier while it is already out, over a tunnel that no
 * longer works. Sent over ordinary internet the answer arrives, which is what makes the answer worth
 * waiting for. The price is that a failure has to put back exactly what was taken down, and only that.
 */
object Leaving {

    private const val UNREACHABLE =
        "Vaier couldn't be reached, so this phone is still in it. Check the connection and try again."

    fun after(outcome: LeaveOutcome, wasConnected: Boolean): LeaveSteps = when (outcome) {
        LeaveOutcome.REMOVED -> LeaveSteps(forget = true, reconnect = false, notice = null)
        LeaveOutcome.UNREACHABLE ->
            LeaveSteps(forget = false, reconnect = wasConnected, notice = UNREACHABLE)
    }
}
