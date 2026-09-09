package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandingWatchTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    // When the phone is worth asking about at all.

    @Test
    fun `a connection that has just been in touch is left alone`() {
        assertFalse(
            StandingWatch.worthAsking(
                now = now,
                latestHandshakeEpochMillis = now - 30_000,
                connectedSinceEpochMillis = now - 60 * minute,
                lastAskedEpochMillis = 0,
            )
        )
    }

    @Test
    fun `a connection silent for more than three minutes is worth asking about`() {
        assertTrue(
            StandingWatch.worthAsking(
                now = now,
                latestHandshakeEpochMillis = now - 4 * minute,
                connectedSinceEpochMillis = now - 60 * minute,
                lastAskedEpochMillis = 0,
            )
        )
    }

    @Test
    fun `a connection that has never been in touch is given three minutes to manage it`() {
        assertFalse(
            StandingWatch.worthAsking(
                now = now,
                latestHandshakeEpochMillis = 0,
                connectedSinceEpochMillis = now - 2 * minute,
                lastAskedEpochMillis = 0,
            )
        )
    }

    @Test
    fun `a connection that has never been in touch and has had its three minutes is worth asking about`() {
        assertTrue(
            StandingWatch.worthAsking(
                now = now,
                latestHandshakeEpochMillis = 0,
                connectedSinceEpochMillis = now - 4 * minute,
                lastAskedEpochMillis = 0,
            )
        )
    }

    @Test
    fun `a phone that asked a minute ago does not ask again`() {
        assertFalse(
            StandingWatch.worthAsking(
                now = now,
                latestHandshakeEpochMillis = now - 30 * minute,
                connectedSinceEpochMillis = now - 60 * minute,
                lastAskedEpochMillis = now - minute,
            )
        )
    }

    @Test
    fun `a phone that asked more than five minutes ago may ask again`() {
        assertTrue(
            StandingWatch.worthAsking(
                now = now,
                latestHandshakeEpochMillis = now - 30 * minute,
                connectedSinceEpochMillis = now - 60 * minute,
                lastAskedEpochMillis = now - 6 * minute,
            )
        )
    }

    // What a silent connection's answer means.

    @Test
    fun `a phone Vaier no longer has forgets its place and is told why`() {
        val steps = StandingWatch.afterSilence(Standing.REMOVED)

        assertTrue(steps.forget)
        assertEquals("Vaier removed this phone. Join again to reconnect.", steps.notice)
    }

    @Test
    fun `a phone Vaier no longer has does not put its connection back`() {
        assertFalse(StandingWatch.afterSilence(Standing.REMOVED).reconnect)
    }

    @Test
    fun `a phone removed while nobody was looking is told on the lock screen`() {
        assertTrue(StandingWatch.afterSilence(Standing.REMOVED).notify)
    }

    @Test
    fun `a phone that is still in Vaier goes back on and says nothing`() {
        val steps = StandingWatch.afterSilence(Standing.MEMBER)

        assertTrue(steps.reconnect)
        assertFalse(steps.forget)
        assertFalse(steps.notify)
        assertNull(steps.notice)
    }

    @Test
    fun `a phone that could not reach Vaier keeps its place and goes back on`() {
        val steps = StandingWatch.afterSilence(Standing.UNREACHABLE)

        assertTrue(steps.reconnect)
        assertFalse(steps.forget)
        assertNull(steps.notice)
    }

    // What the answer means when the app was opened with the connection already off.

    @Test
    fun `a phone opened after Vaier dropped it lands back at the start and is told why`() {
        val steps = StandingWatch.afterOpening(Standing.REMOVED)

        assertTrue(steps.forget)
        assertEquals("Vaier removed this phone. Join again to reconnect.", steps.notice)
    }

    @Test
    fun `a phone whose owner is looking at it is not sent a notification as well`() {
        assertFalse(StandingWatch.afterOpening(Standing.REMOVED).notify)
    }

    @Test
    fun `an answer to a phone that was not connected never connects it`() {
        assertFalse(StandingWatch.afterOpening(Standing.REMOVED).reconnect)
        assertFalse(StandingWatch.afterOpening(Standing.MEMBER).reconnect)
        assertFalse(StandingWatch.afterOpening(Standing.UNREACHABLE).reconnect)
    }

    @Test
    fun `a phone still in Vaier that was simply opened is left exactly as it was`() {
        val steps = StandingWatch.afterOpening(Standing.MEMBER)

        assertFalse(steps.forget)
        assertNull(steps.notice)
    }

    @Test
    fun `a phone that could not reach Vaier on opening is left exactly as it was`() {
        val steps = StandingWatch.afterOpening(Standing.UNREACHABLE)

        assertFalse(steps.forget)
        assertNull(steps.notice)
    }

    // The words on the notification.

    @Test
    fun `the notification says which phone this happened to and what to do next`() {
        assertEquals(
            "Pixel 8 was removed from Vaier by whoever runs it. Open the app to join again.",
            StandingWatch.removalText("Pixel 8"),
        )
    }

    @Test
    fun `a phone with no name still reads as a sentence`() {
        assertEquals(
            "This phone was removed from Vaier by whoever runs it. Open the app to join again.",
            StandingWatch.removalText("  "),
        )
    }
}
