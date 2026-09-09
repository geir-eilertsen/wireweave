package net.vaier.app

/** One message off a server-sent event stream. */
data class ServerEvent(val name: String, val data: String)

/**
 * The line-by-line half of a server-sent event stream, kept away from the socket so it can be read
 * as plain text in a test. A caller feeds it whatever the connection hands over, one line at a time,
 * and gets an event back on the blank line that ends one.
 */
class EventStream {

    private var name = ""
    private val data = StringBuilder()
    private var dataSeen = false
    private var started = false

    /** The event this line finished, or null while one is still arriving. */
    fun accept(rawLine: String): ServerEvent? {
        val line = rawLine.removeSuffix("\r")

        if (line.isEmpty()) return finish()
        if (line.startsWith(":")) return null

        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> {
                if (dataSeen) data.append('\n')
                data.append(value)
                dataSeen = true
            }
            else -> return null // retry, id and anything else this app has no use for
        }
        started = true
        return null
    }

    private fun finish(): ServerEvent? {
        if (!started) return null
        val event = ServerEvent(name, data.toString())
        name = ""
        data.setLength(0)
        dataSeen = false
        started = false
        return event
    }
}
