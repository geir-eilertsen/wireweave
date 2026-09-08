package net.vaier.app

import android.content.Context
import com.wireguard.android.backend.Backend
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

/** What the tunnel is doing right now, as the home screen needs to say it. */
data class TunnelStatus(
    val up: Boolean,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val latestHandshakeEpochMillis: Long = 0,
)

private class VaierTunnel : Tunnel {
    override fun getName() = "vaier"
    override fun onStateChange(newState: Tunnel.State) = Unit
}

/** The one tunnel this app owns, and the WireGuard backend that carries it. */
class TunnelController(context: Context) {

    private val backend: Backend = GoBackend(context.applicationContext)
    private val tunnel = VaierTunnel()

    fun configOf(text: String): Config = text.byteInputStream().bufferedReader().use { Config.parse(it) }

    fun setUp(config: Config) {
        backend.setState(tunnel, Tunnel.State.UP, config)
    }

    fun setDown(config: Config) {
        backend.setState(tunnel, Tunnel.State.DOWN, config)
    }

    fun status(): TunnelStatus {
        val up = backend.getState(tunnel) == Tunnel.State.UP
        if (!up) return TunnelStatus(up = false)

        val statistics = backend.getStatistics(tunnel)
        val handshake = statistics.peers()
            .mapNotNull { statistics.peer(it) }
            .maxOfOrNull { it.latestHandshakeEpochMillis() }
            ?: 0L
        return TunnelStatus(
            up = true,
            rxBytes = statistics.totalRx(),
            txBytes = statistics.totalTx(),
            latestHandshakeEpochMillis = handshake,
        )
    }
}
