package net.vaier.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import net.vaier.app.Membership
import net.vaier.app.PendingEnrolment
import net.vaier.app.TunnelStatus

// The Explorer's own palette — Vaier commits to one warm dark look, so the app does too.
private val VaierAmber = Color(0xFFD9A05B)
private val VaierPanel = Color(0xFF191510)
private val VaierCard = Color(0xFF201A13)
private val VaierText = Color(0xFFEAE0D2)
private val VaierTextDim = Color(0xFF7A6A53)

@Composable
fun VaierApp(
    membership: Membership?,
    pending: PendingEnrolment?,
    status: TunnelStatus,
    notice: String?,
    suggestedDeviceName: String,
    onJoin: (address: String, deviceName: String) -> Unit,
    onResumeEnrolment: (PendingEnrolment) -> Unit,
    onConnectedChange: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colours = darkColorScheme(
        primary = VaierAmber, secondary = VaierAmber, onPrimary = VaierPanel,
        background = VaierPanel, onBackground = VaierText,
        surface = VaierPanel, onSurface = VaierText,
        surfaceVariant = VaierCard, onSurfaceVariant = VaierTextDim,
    )

    MaterialTheme(colorScheme = colours) {
        if (membership == null) {
            SetupScreen(
                pending = pending,
                notice = notice,
                suggestedDeviceName = suggestedDeviceName,
                onJoin = onJoin,
                onResumeEnrolment = onResumeEnrolment,
            )
        } else {
            HomeScreen(
                membership = membership,
                status = status,
                notice = notice,
                onConnectedChange = onConnectedChange,
                onLeave = onLeave,
                onRefresh = onRefresh,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    pending: PendingEnrolment?,
    notice: String?,
    suggestedDeviceName: String,
    onJoin: (String, String) -> Unit,
    onResumeEnrolment: (PendingEnrolment) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(pending?.address.orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(pending?.deviceName?.ifBlank { null } ?: suggestedDeviceName) }

    Scaffold(topBar = { TopAppBar(title = { Text("Vaier") }) }) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Join the fleet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This phone mints its own key and never shares it. Vaier only ever learns the public half.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Vaier address") },
                placeholder = { Text("vaier.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Device name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onJoin(address, deviceName) },
                enabled = address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Join")
            }

            if (pending != null) {
                Text(
                    "Waiting for an operator to approve ${pending.deviceName} at ${pending.address}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { onResumeEnrolment(pending) }) {
                    Text("Open Vaier again")
                }
            }

            Notice(notice)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    membership: Membership,
    status: TunnelStatus,
    notice: String?,
    onConnectedChange: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onRefresh: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // The only polling in the app, and it is a local call into the tunnel backend rather than the
    // network. It stops the moment the screen is not on show.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onRefresh()
                delay(3000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vaier") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Leave the fleet") },
                            onClick = {
                                menuOpen = false
                                onLeave()
                            },
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(membership.deviceName, style = MaterialTheme.typography.headlineSmall)

            Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Switch(checked = status.up, onCheckedChange = onConnectedChange)
                    }

                    if (status.up) {
                        HorizontalDivider()
                        Field("Last handshake", sinceHandshake(status.latestHandshakeEpochMillis))
                        Field("Received", bytes(status.rxBytes))
                        Field("Sent", bytes(status.txBytes))
                    }
                }
            }

            Field("Vaier", membership.address)
            Field("Address on the fleet", membership.tunnelAddress)

            Spacer(Modifier.height(4.dp))
            Notice(notice)
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun Notice(notice: String?) {
    if (notice.isNullOrBlank()) return
    Text(
        notice,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun bytes(count: Long): String = when {
    count < 1024 -> "$count B"
    count < 1024 * 1024 -> "%.1f kB".format(count / 1024.0)
    count < 1024L * 1024 * 1024 -> "%.1f MB".format(count / (1024.0 * 1024))
    else -> "%.2f GB".format(count / (1024.0 * 1024 * 1024))
}

private fun sinceHandshake(epochMillis: Long): String {
    if (epochMillis <= 0) return "never"
    val seconds = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "${seconds / 3600} h ago"
    }
}

