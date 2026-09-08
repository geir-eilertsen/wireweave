package net.vaier.app

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** What Vaier sent back through the `vaier://enrol` deep link, once we know it is ours. */
data class Enrolment(
    val peerId: String,
    val peerName: String,
    val peerType: String,
    val tunnelAddress: String,
    val configText: String,
)

/** A refusal a person can read, because the only place it can be shown is the screen. */
class EnrolmentException(message: String) : Exception(message)

/**
 * Reads the deep link's fragment: base64url of a WireGuard config that Vaier deliberately left
 * without a private key. This app supplies the private half, which never leaves the phone.
 */
object EnrolmentPayload {

    private const val MARKER = "# VAIER:"

    fun parse(fragment: String, publicKey: String, privateKey: String): Enrolment {
        val encoded = fragment.trim().removePrefix("#")
        if (encoded.isEmpty()) throw EnrolmentException("That enrolment link carried nothing.")

        val conf = decode(encoded)
        val metadata = metadataIn(conf)

        if (metadata.optString("publicKey") != publicKey) {
            throw EnrolmentException("That enrolment was made for a different device. Start again from Setup.")
        }
        if (conf.lineSequence().any { it.trimStart().startsWith("PrivateKey", ignoreCase = true) }) {
            throw EnrolmentException("That enrolment carries a private key. Vaier never sends one, so it is refused.")
        }

        return Enrolment(
            peerId = metadata.optString("id"),
            peerName = metadata.optString("name"),
            peerType = metadata.optString("peerType"),
            tunnelAddress = valueOf(conf, "Address"),
            configText = withPrivateKey(conf, privateKey),
        )
    }

    private fun decode(encoded: String): String =
        try {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw EnrolmentException("That enrolment link is damaged.")
        }

    private fun metadataIn(conf: String): JSONObject {
        val line = conf.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) }
            ?: throw EnrolmentException("That enrolment is not from Vaier.")
        return try {
            JSONObject(line.trimStart().removePrefix(MARKER))
        } catch (e: JSONException) {
            throw EnrolmentException("That enrolment is not from Vaier.")
        }
    }

    private fun withPrivateKey(conf: String, privateKey: String): String {
        val lines = conf.lines().toMutableList()
        val section = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (section < 0) throw EnrolmentException("That enrolment has no [Interface] section.")
        lines.add(section + 1, "PrivateKey = $privateKey")
        return lines.joinToString("\n")
    }

    private fun valueOf(conf: String, key: String): String =
        conf.lineSequence()
            .filter { it.contains('=') }
            .firstOrNull { it.substringBefore('=').trim().equals(key, ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
}
