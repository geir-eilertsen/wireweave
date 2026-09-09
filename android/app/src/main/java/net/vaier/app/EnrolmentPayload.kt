package net.vaier.app

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/** What Vaier sent down this phone's own stream once it was approved, after we know it is ours. */
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
 * Reads the `approved` event's data: base64url of a WireGuard config that Vaier deliberately left
 * without a private key. This app supplies the private half, which never leaves the phone.
 */
object EnrolmentPayload {

    private const val MARKER = "# VAIER:"

    fun parse(encoded: String, publicKey: String, privateKey: String): Enrolment {
        if (encoded.isBlank()) throw EnrolmentException("Vaier approved this phone but sent nothing with it.")

        val conf = decode(encoded.trim())
        val metadata = metadataIn(conf)

        if (metadata.optString("publicKey") != publicKey) {
            throw EnrolmentException("That was meant for a different device. Start again.")
        }
        if (conf.lineSequence().any { it.trimStart().startsWith("PrivateKey", ignoreCase = true) }) {
            throw EnrolmentException("That carries a private key. Vaier never sends one, so it is refused.")
        }

        return Enrolment(
            peerId = metadata.optString("id"),
            peerName = metadata.optString("name"),
            peerType = metadata.optString("peerType"),
            tunnelAddress = valueOf(conf, "Address"),
            configText = withPrivateKey(conf, privateKey),
        )
    }

    /** The shared secret only this phone and Vaier hold — what leaving proves itself with. */
    fun presharedKeyIn(configText: String): String = valueOf(configText, "PresharedKey")

    private fun decode(encoded: String): String =
        try {
            String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw EnrolmentException("What Vaier sent back is damaged. Try joining again.")
        }

    private fun metadataIn(conf: String): JSONObject {
        val line = conf.lineSequence().firstOrNull { it.trimStart().startsWith(MARKER) }
            ?: throw EnrolmentException("That answer is not from Vaier, so it is refused.")
        return try {
            JSONObject(line.trimStart().removePrefix(MARKER))
        } catch (e: JSONException) {
            throw EnrolmentException("That answer is not from Vaier, so it is refused.")
        }
    }

    private fun withPrivateKey(conf: String, privateKey: String): String {
        val lines = conf.lines().toMutableList()
        val section = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (section < 0) throw EnrolmentException("What Vaier sent has no [Interface] section.")
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
