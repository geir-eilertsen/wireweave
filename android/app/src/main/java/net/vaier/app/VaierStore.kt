package net.vaier.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.wireguard.crypto.KeyPair

/** This phone's settled place in Vaier. */
data class Membership(
    val address: String,
    val deviceName: String,
    val tunnelAddress: String,
    val configText: String,
)

/**
 * Everything this phone knows about its place in Vaier, in app-private storage — the same bar the
 * WireGuard app sets. The private key is minted here and never leaves.
 */
class VaierStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vaier", Context.MODE_PRIVATE)

    val privateKey: String? get() = prefs.getString(PRIVATE_KEY, null)
    val publicKey: String? get() = prefs.getString(PUBLIC_KEY, null)

    /** The host stamped into this APK, once it has been read out of the file. */
    val stampedAddress: String? get() = prefs.getString(STAMPED_ADDRESS, null)

    fun rememberStamp(address: String) = prefs.edit { putString(STAMPED_ADDRESS, address) }

    /** Only a request Vaier has answered is one this phone can wait on, so all five parts must be here. */
    val pending: PendingJoin?
        get() {
            if (membership != null) return null
            val address = prefs.getString(ADDRESS, null) ?: return null
            val publicKey = publicKey ?: return null
            val code = prefs.getString(CODE, null) ?: return null
            val ticket = prefs.getString(TICKET, null) ?: return null
            return PendingJoin(
                address = address,
                deviceName = prefs.getString(DEVICE_NAME, null).orEmpty(),
                publicKey = publicKey,
                code = code,
                ticket = ticket,
                expiresAtEpochMillis = prefs.getLong(EXPIRES_AT, 0),
            )
        }

    val membership: Membership?
        get() {
            val configText = prefs.getString(CONFIG, null) ?: return null
            return Membership(
                address = prefs.getString(ADDRESS, null).orEmpty(),
                deviceName = prefs.getString(DEVICE_NAME, null).orEmpty(),
                tunnelAddress = prefs.getString(TUNNEL_ADDRESS, null).orEmpty(),
                configText = configText,
            )
        }

    /** Mints a fresh keypair for the ask about to be made, and hands back the half Vaier may see. */
    fun beginJoin(address: String, deviceName: String): String {
        val keys = KeyPair()
        prefs.edit {
            forget()
            putString(PRIVATE_KEY, keys.privateKey.toBase64())
            putString(PUBLIC_KEY, keys.publicKey.toBase64())
            putString(ADDRESS, address)
            putString(DEVICE_NAME, deviceName)
        }
        return keys.publicKey.toBase64()
    }

    /** Vaier answered: the code to show, the ticket to wait on, and when the wait runs out. */
    fun awaitApproval(code: String, ticket: String, expiresAtEpochMillis: Long) = prefs.edit {
        putString(CODE, code)
        putString(TICKET, ticket)
        putLong(EXPIRES_AT, expiresAtEpochMillis)
    }

    fun complete(enrolment: Enrolment) = prefs.edit {
        putString(CONFIG, enrolment.configText)
        putString(TUNNEL_ADDRESS, enrolment.tunnelAddress)
        putString(DEVICE_NAME, enrolment.peerName)
        remove(CODE)
        remove(TICKET)
        remove(EXPIRES_AT)
    }

    /** Drops this phone's place and its keys — after leaving, after a refusal, or on cancel. */
    fun forget() = prefs.edit { forget() }

    /** The stamp survives: it describes the APK this phone is running, not where it belongs. */
    private fun SharedPreferences.Editor.forget() {
        val stamp = stampedAddress
        clear()
        stamp?.let { putString(STAMPED_ADDRESS, it) }
    }

    private companion object {
        const val PRIVATE_KEY = "privateKey"
        const val PUBLIC_KEY = "publicKey"
        const val ADDRESS = "address"
        const val DEVICE_NAME = "deviceName"
        const val TUNNEL_ADDRESS = "tunnelAddress"
        const val CONFIG = "config"
        const val STAMPED_ADDRESS = "stampedAddress"
        const val CODE = "joinCode"
        const val TICKET = "joinTicket"
        const val EXPIRES_AT = "joinExpiresAt"
    }
}
