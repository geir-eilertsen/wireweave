package net.vaier.app

import android.content.Context
import androidx.core.content.edit
import com.wireguard.crypto.KeyPair

/** The keys this phone minted, waiting for an operator to approve them. */
data class PendingEnrolment(
    val address: String,
    val deviceName: String,
    val publicKey: String,
)

/** This phone's settled place in the fleet. */
data class Membership(
    val address: String,
    val deviceName: String,
    val tunnelAddress: String,
    val configText: String,
)

/**
 * Everything this phone knows about its place in the fleet, in app-private storage — the same bar
 * the WireGuard app sets. The private key is minted here and never leaves.
 */
class VaierStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("vaier", Context.MODE_PRIVATE)

    val privateKey: String? get() = prefs.getString(PRIVATE_KEY, null)
    val publicKey: String? get() = prefs.getString(PUBLIC_KEY, null)

    val pending: PendingEnrolment?
        get() {
            if (membership != null) return null
            val address = prefs.getString(ADDRESS, null) ?: return null
            val publicKey = publicKey ?: return null
            return PendingEnrolment(address, prefs.getString(DEVICE_NAME, null).orEmpty(), publicKey)
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

    /** Mints a fresh keypair and remembers what we are about to ask Vaier for. */
    fun beginEnrolment(address: String, deviceName: String): PendingEnrolment {
        val keys = KeyPair()
        prefs.edit {
            clear()
            putString(PRIVATE_KEY, keys.privateKey.toBase64())
            putString(PUBLIC_KEY, keys.publicKey.toBase64())
            putString(ADDRESS, address)
            putString(DEVICE_NAME, deviceName)
        }
        return PendingEnrolment(address, deviceName, keys.publicKey.toBase64())
    }

    fun complete(enrolment: Enrolment) {
        prefs.edit {
            putString(CONFIG, enrolment.configText)
            putString(TUNNEL_ADDRESS, enrolment.tunnelAddress)
            putString(DEVICE_NAME, enrolment.peerName)
        }
    }

    fun leave() = prefs.edit { clear() }

    private companion object {
        const val PRIVATE_KEY = "privateKey"
        const val PUBLIC_KEY = "publicKey"
        const val ADDRESS = "address"
        const val DEVICE_NAME = "deviceName"
        const val TUNNEL_ADDRESS = "tunnelAddress"
        const val CONFIG = "config"
    }
}
