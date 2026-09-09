package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventStreamTest {

    private val stream = EventStream()

    /** Feeds every line and returns the events that completed along the way. */
    private fun feed(vararg lines: String): List<ServerEvent> = lines.mapNotNull(stream::accept)

    @Test
    fun `an event completes on the blank line, not before`() {
        assertNull(stream.accept("event: approved"))
        assertNull(stream.accept("data: WW8="))

        assertEquals(ServerEvent("approved", "WW8="), stream.accept(""))
    }

    @Test
    fun `a refusal carries an empty data line`() {
        assertEquals(listOf(ServerEvent("refused", "")), feed("event: refused", "data:", ""))
    }

    @Test
    fun `two events on one connection are read one after the other`() {
        val events = feed("event: one", "data: first", "", "event: two", "data: second", "")

        assertEquals(listOf(ServerEvent("one", "first"), ServerEvent("two", "second")), events)
    }

    @Test
    fun `several data lines join with newlines`() {
        val events = feed("event: approved", "data: one", "data: two", "data: three", "")

        assertEquals(listOf(ServerEvent("approved", "one\ntwo\nthree")), events)
    }

    @Test
    fun `comment lines and retry lines are ignored`() {
        val events = feed(": keep alive", "retry: 3000", "event: approved", "data: payload", "")

        assertEquals(listOf(ServerEvent("approved", "payload")), events)
    }

    @Test
    fun `a blank line with nothing before it completes nothing`() {
        assertEquals(emptyList<ServerEvent>(), feed("", "", ": ping", ""))
    }

    @Test
    fun `only the first space after the colon belongs to the framing`() {
        assertEquals(listOf(ServerEvent("approved", " padded")), feed("event: approved", "data:  padded", ""))
    }

    @Test
    fun `a field with no space after the colon reads the same`() {
        assertEquals(listOf(ServerEvent("approved", "payload")), feed("event:approved", "data:payload", ""))
    }

    @Test
    fun `a data line with no event name still completes`() {
        assertEquals(listOf(ServerEvent("", "payload")), feed("data: payload", ""))
    }

    @Test
    fun `an unfinished event does not leak into the next one`() {
        val events = feed("event: approved", "data: first", "", "data: second", "")

        assertEquals(listOf(ServerEvent("approved", "first"), ServerEvent("", "second")), events)
    }

    @Test
    fun `a trailing carriage return is not part of the data`() {
        assertEquals(listOf(ServerEvent("approved", "payload")), feed("event: approved\r", "data: payload\r", "\r"))
    }
}
