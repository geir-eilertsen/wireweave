package net.vaier.app

/** The Vaier server's address, as a person types it and as this app must store it. */
object VaierAddress {

    private val HOST = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*(:\\d{1,5})?")

    /** The bare host — no scheme, no path, lower case — or null if what was typed cannot be one. */
    fun normalise(typed: String): String? {
        val host = typed
            .filterNot(Char::isWhitespace)
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trimEnd('.')
        return host.takeIf { HOST.matches(it) }
    }
}
