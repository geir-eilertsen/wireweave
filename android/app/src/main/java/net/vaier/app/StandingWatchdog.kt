package net.vaier.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Notices, while the connection is on, that Vaier does not have this phone any more.
 *
 * <p>It lives for as long as the app's process rather than any screen, because the connection does:
 * the tunnel runs in this process, so a phone with the switch on is still worth watching hours after
 * the last time anybody looked at it. [StandingWatch] owns every judgement made here; this class only
 * keeps time and does as it is told.
 */
class StandingWatchdog(
    private val store: VaierStore,
    private val tunnels: TunnelController,
    private val vaier: VaierClient,
    private val notification: RemovalNotification,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null
    private var connectedSince = 0L
    private var lastAsked = 0L

    /** Set by whichever screen is on show, so a removal reaches the person in front of it too. */
    @Volatile
    var onRemoved: (() -> Unit)? = null

    @Volatile
    private var unspoken: String? = null

    /**
     * What a removal owes the person, if it has not been said to them yet. Kept because most removals
     * are found with no screen on show: whoever opens the app next has to be told why it is asking to
     * join again, and the notification they may never have seen is not that.
     */
    fun takeNotice(): String? = unspoken.also { unspoken = null }

    /** Starts watching a connection that has just come up. Saying it twice changes nothing. */
    fun watch() {
        if (watching?.isActive == true) return
        connectedSince = System.currentTimeMillis()
        watching = scope.launch {
            while (true) {
                delay(BEAT_MILLIS)
                if (!beat()) return@launch
            }
        }
    }

    /** Stops watching, because the person turned the connection off. */
    fun rest() {
        watching?.cancel()
        watching = null
    }

    /** One look at the tunnel. False when there is nothing left to watch. */
    private suspend fun beat(): Boolean {
        val membership = store.membership ?: return false
        val status = runCatching { tunnels.status() }.getOrNull() ?: return true
        if (!status.up) return false

        val now = System.currentTimeMillis()
        val worthAsking = StandingWatch.worthAsking(
            now = now,
            latestHandshakeEpochMillis = status.latestHandshakeEpochMillis,
            connectedSinceEpochMillis = connectedSince,
            lastAskedEpochMillis = lastAsked,
        )
        if (!worthAsking) return true

        lastAsked = now
        return ask(membership)
    }

    /**
     * Takes the connection down, asks over ordinary internet, and does what the answer says. The
     * connection has to come down first: through it there is nothing to ask, and no answer would come
     * back.
     */
    private suspend fun ask(membership: Membership): Boolean {
        val config = runCatching { tunnels.configOf(membership.configText) }.getOrNull() ?: return false
        runCatching { tunnels.setDown(config) }

        val outcome = vaier.standing(
            membership.address,
            store.publicKey.orEmpty(),
            EnrolmentPayload.presharedKeyIn(membership.configText),
        )
        val steps = StandingWatch.afterSilence(outcome)

        if (steps.reconnect) {
            runCatching { tunnels.setUp(config) }
            connectedSince = System.currentTimeMillis()
        }
        if (!steps.forget) return true

        store.forget()
        unspoken = steps.notice
        if (steps.notify) runCatching { notification.post(membership.deviceName) }
        onRemoved?.invoke()
        return false
    }

    private companion object {
        /** How often to look. Local and cheap: it reads the tunnel, not the network. */
        const val BEAT_MILLIS = 60_000L
    }
}
