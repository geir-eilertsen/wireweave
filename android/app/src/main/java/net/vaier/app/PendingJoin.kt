package net.vaier.app

/**
 * This phone waiting to be let in: the key it minted, the code on its screen, and the ticket only it
 * holds. It carries its own deadline so the wait ends on the phone too, rather than only when Vaier
 * quietly drops the request.
 */
data class PendingJoin(
    val address: String,
    val deviceName: String,
    val publicKey: String,
    val code: String,
    val ticket: String,
    val expiresAtEpochMillis: Long,
) {

    /** Expiry is exclusive, the same instant Vaier stops honouring the ticket. */
    fun hasExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis

    companion object {

        fun deadlineOf(answeredAtEpochMillis: Long, secondsLeft: Long): Long =
            answeredAtEpochMillis + secondsLeft * 1000
    }
}
