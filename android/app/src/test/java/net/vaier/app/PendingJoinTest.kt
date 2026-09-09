package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingJoinTest {

    private fun waiting(expiresAt: Long) = PendingJoin(
        address = "vaier.example.com",
        deviceName = "Pixel 8",
        publicKey = "eA1uZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTiE=",
        code = "0421",
        ticket = "3f9c",
        expiresAtEpochMillis = expiresAt,
    )

    @Test
    fun `a request with time left has not run out`() {
        assertFalse(waiting(expiresAt = 2_000).hasExpired(1_999))
    }

    @Test
    fun `the deadline itself has run out, the same instant Vaier drops it`() {
        assertTrue(waiting(expiresAt = 2_000).hasExpired(2_000))
    }

    @Test
    fun `a request long past its deadline has run out`() {
        assertTrue(waiting(expiresAt = 2_000).hasExpired(9_999))
    }

    @Test
    fun `a request with no deadline remembered has run out, rather than waiting forever`() {
        assertTrue(waiting(expiresAt = 0).hasExpired(1))
    }

    @Test
    fun `the deadline is counted from when Vaier answered`() {
        assertEquals(1_000 + 600_000, PendingJoin.deadlineOf(answeredAtEpochMillis = 1_000, secondsLeft = 600))
    }
}
