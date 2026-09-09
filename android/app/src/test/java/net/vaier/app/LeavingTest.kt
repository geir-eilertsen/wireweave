package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeavingTest {

    @Test
    fun `a phone Vaier has removed forgets its place`() {
        val steps = Leaving.after(LeaveOutcome.REMOVED, wasConnected = true)

        assertTrue(steps.forget)
        assertNull(steps.notice)
    }

    @Test
    fun `a phone Vaier has removed does not reconnect to what it just left`() {
        assertFalse(Leaving.after(LeaveOutcome.REMOVED, wasConnected = true).reconnect)
    }

    @Test
    fun `a phone that could not reach Vaier keeps its place and says why`() {
        val steps = Leaving.after(LeaveOutcome.UNREACHABLE, wasConnected = false)

        assertFalse(steps.forget)
        assertEquals(
            "Vaier couldn't be reached, so this phone is still in it. Check the connection and try again.",
            steps.notice,
        )
    }

    @Test
    fun `a connection taken down to send the request is put back when the request fails`() {
        assertTrue(Leaving.after(LeaveOutcome.UNREACHABLE, wasConnected = true).reconnect)
    }

    @Test
    fun `a phone that was not connected is not connected by a failure`() {
        assertFalse(Leaving.after(LeaveOutcome.UNREACHABLE, wasConnected = false).reconnect)
    }
}
