package net.vaier.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import net.vaier.app.Membership
import net.vaier.app.PendingJoin
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
    pending: PendingJoin?,
    stampedAddress: String?,
    status: TunnelStatus,
    notice: String?,
    busy: Boolean,
    suggestedDeviceName: String,
    onJoin: (address: String, deviceName: String) -> Unit,
    onApproveHere: (PendingJoin) -> Unit,
    onCancelJoin: () -> Unit,
    onWait: suspend (PendingJoin) -> Unit,
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
        when {
            membership != null -> HomeScreen(
                membership = membership,
                status = status,
                notice = notice,
                busy = busy,
                onConnectedChange = onConnectedChange,
                onLeave = onLeave,
                onRefresh = onRefresh,
            )

            pending != null -> WaitingScreen(
                waiting = pending,
                notice = notice,
                onApproveHere = onApproveHere,
                onCancel = onCancelJoin,
                onWait = onWait,
            )

            else -> SetupScreen(
                stampedAddress = stampedAddress,
                notice = notice,
                busy = busy,
                suggestedDeviceName = suggestedDeviceName,
                onJoin = onJoin,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    stampedAddress: String?,
    notice: String?,
    busy: Boolean,
    suggestedDeviceName: String,
    onJoin: (String, String) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(suggestedDeviceName) }

    Scaffold(topBar = { TopAppBar(title = { Text("Vaier") }) }) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Join Vaier", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This phone asks to join. Whoever runs Vaier says yes, and you are in.",
                style = MaterialTheme.typography.bodyLarge,
            )

            // A download served by Vaier carries its server's name, so there is nothing to type.
            // The field is the fallback for a sideloaded build, and the two never show together.
            if (stampedAddress == null) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Vaier address") },
                    placeholder = { Text("vaier.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("Name this phone") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { onJoin(stampedAddress ?: address, deviceName) },
                enabled = !busy && (stampedAddress != null || address.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Asking…" else "Ask to join")
            }

            Notice(notice)
        }
    }
}

/**
 * The code is the whole screen. Everything else here is quiet on purpose: a person reading it out to
 * someone across a room should never have to hunt for the four digits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaitingScreen(
    waiting: PendingJoin,
    notice: String?,
    onApproveHere: (PendingJoin) -> Unit,
    onCancel: () -> Unit,
    onWait: suspend (PendingJoin) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    // Vaier pushes the answer down this phone's own stream, so the app never asks twice. The wait
    // lives exactly as long as the screen does.
    LaunchedEffect(waiting.ticket, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { onWait(waiting) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Vaier") }) }) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Text(
                waiting.code,
                color = VaierAmber,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 60.sp,
                letterSpacing = 14.sp,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "Read this code out to whoever runs Vaier.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "The moment they say yes, this phone is in. Leave the app open.",
                style = MaterialTheme.typography.bodyMedium,
                color = VaierTextDim,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.weight(1f))
            Notice(notice)

            Button(
                onClick = { onApproveHere(waiting) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("I run Vaier — approve it here")
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    membership: Membership,
    status: TunnelStatus,
    notice: String?,
    busy: Boolean,
    onConnectedChange: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onRefresh: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
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

    if (confirmLeave) {
        LeaveDialog(
            onConfirm = {
                confirmLeave = false
                onLeave()
            },
            onDismiss = { confirmLeave = false },
        )
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
                            text = { Text("Leave Vaier") },
                            onClick = {
                                menuOpen = false
                                confirmLeave = true
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
            Text(membership.deviceName, style = MaterialTheme.typography.titleMedium, color = VaierTextDim)

            Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (status.up) "Connected" else "Not connected",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (status.up) "Everything this phone does online goes through Vaier."
                            else "Turn it on to use Vaier.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VaierTextDim,
                        )
                    }
                    Switch(checked = status.up, onCheckedChange = { if (!busy) onConnectedChange(it) })
                }
            }

            Notice(notice)

            Spacer(Modifier.weight(1f))
            Details {
                Field("Vaier address", membership.address)
                Field("This phone's address", membership.tunnelAddress)
                Field("Last handshake", sinceHandshake(status.latestHandshakeEpochMillis))
                Field("Received", bytes(status.rxBytes))
                Field("Sent", bytes(status.txBytes))
            }
        }
    }
}

@Composable
private fun LeaveDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave Vaier?") },
        text = {
            Text("This phone will be removed from Vaier. To come back you'll need to join again and be approved.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Leave") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay") } },
    )
}

/** The one place technical words are allowed, and it is shut until somebody asks for it. */
@Composable
private fun Details(content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text("Details", style = MaterialTheme.typography.labelLarge)
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
            )
        }
        if (open) {
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = VaierTextDim)
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
