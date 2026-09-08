package net.vaier.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vaier.app.ui.VaierApp

class MainActivity : ComponentActivity() {

    private lateinit var store: VaierStore
    private lateinit var tunnels: TunnelController

    private val membership = mutableStateOf<Membership?>(null)
    private val pending = mutableStateOf<PendingEnrolment?>(null)
    private val status = mutableStateOf(TunnelStatus(up = false))
    private val notice = mutableStateOf<String?>(null)

    private val vpnPermission = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            bringUp()
        } else {
            notice.value = "Android did not grant permission for a VPN, so the tunnel stayed down."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VaierStore(this)
        tunnels = TunnelController(this)
        readStore()

        setContent {
            VaierApp(
                membership = membership.value,
                pending = pending.value,
                status = status.value,
                notice = notice.value,
                suggestedDeviceName = Build.MODEL.orEmpty().ifBlank { "Phone" },
                onJoin = ::join,
                onResumeEnrolment = ::openEnrolment,
                onConnectedChange = ::setConnected,
                onLeave = ::leave,
                onRefresh = ::refreshStatus,
            )
        }

        receiveEnrolment(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveEnrolment(intent)
    }

    private fun readStore() {
        membership.value = store.membership
        pending.value = store.pending
    }

    // Setup -> keys minted here -> operator approves in a browser -> vaier://enrol comes back.

    private fun join(typedAddress: String, deviceName: String) {
        val address = VaierAddress.normalise(typedAddress)
        if (address == null) {
            notice.value = "That does not look like a Vaier address. Try something like vaier.example.com."
            return
        }
        val name = deviceName.trim().ifBlank { Build.MODEL.orEmpty().ifBlank { "Phone" } }
        notice.value = null
        openEnrolment(store.beginEnrolment(address, name))
        readStore()
    }

    private fun openEnrolment(enrolment: PendingEnrolment) {
        val url = Uri.parse("https://${enrolment.address}/explorer.html")
            .buildUpon()
            .appendQueryParameter("enrol", enrolment.publicKey)
            .appendQueryParameter("name", enrolment.deviceName)
            .build()
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, url)
        } catch (e: ActivityNotFoundException) {
            notice.value = "This phone has no browser to sign in with."
        }
    }

    private fun receiveEnrolment(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "vaier" || data.host != "enrol") return
        val fragment = data.fragment.orEmpty()

        val publicKey = store.publicKey
        val privateKey = store.privateKey
        if (publicKey == null || privateKey == null) {
            notice.value = "This phone is not waiting to enrol. Start again from Setup."
            return
        }

        try {
            val enrolment = EnrolmentPayload.parse(fragment, publicKey, privateKey)
            // Prove the tunnel library accepts it before we keep it, so a bad config is a message
            // on the setup screen rather than a failure the first time the switch is touched.
            tunnels.configOf(enrolment.configText)
            store.complete(enrolment)
            notice.value = null
        } catch (e: EnrolmentException) {
            notice.value = e.message
        } catch (e: Exception) {
            notice.value = "Vaier sent a configuration this phone could not read."
        }
        readStore()
    }

    // The tunnel.

    private fun setConnected(connected: Boolean) {
        if (!connected) {
            takeDown()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) vpnPermission.launch(consent) else bringUp()
    }

    private fun bringUp() = onTunnel("Vaier could not bring the tunnel up.") {
        tunnels.setUp(tunnels.configOf(it.configText))
    }

    private fun takeDown() = onTunnel("Vaier could not take the tunnel down.") {
        tunnels.setDown(tunnels.configOf(it.configText))
    }

    private fun onTunnel(failure: String, block: (Membership) -> Unit) {
        val current = membership.value ?: return
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) {
                runCatching { block(current) }.exceptionOrNull()
            }
            notice.value = problem?.let { "$failure ${it.message.orEmpty()}".trim() }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        if (membership.value == null) return
        lifecycleScope.launch {
            status.value = withContext(Dispatchers.IO) {
                runCatching { tunnels.status() }.getOrDefault(TunnelStatus(up = false))
            }
        }
    }

    private fun leave() {
        lifecycleScope.launch {
            membership.value?.let { current ->
                withContext(Dispatchers.IO) {
                    runCatching { tunnels.setDown(tunnels.configOf(current.configText)) }
                }
            }
            store.leave()
            status.value = TunnelStatus(up = false)
            notice.value = null
            readStore()
        }
    }
}
