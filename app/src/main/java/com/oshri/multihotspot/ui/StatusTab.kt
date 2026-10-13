package com.oshri.multihotspot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.engine.BackendKind
import com.oshri.multihotspot.model.Client
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.StaticLease
import kotlinx.coroutines.launch

/** Tab 1: hotspot on/off, problems, connected devices. */
@Composable
fun StatusTab(padding: PaddingValues) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val applied by HotspotController.applied.collectAsStateWithLifecycle()
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val busy by HotspotController.busy.collectAsStateWithLifecycle()
    val error by HotspotController.error.collectAsStateWithLifecycle()
    val backend by HotspotController.backend.collectAsStateWithLifecycle()
    val checked by HotspotController.engineChecked.collectAsStateWithLifecycle()
    val caps = capabilities()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val problems = remember(config, backend) { HotspotController.problems(config, backend) }

    var fixedIpFor by remember { mutableStateOf<Client?>(null) }
    var blockFor by remember { mutableStateOf<Client?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (checked && backend == null) item { EngineSetupCard() }

        item {
            StatusCard(
                state = status.state,
                reason = status.reason,
                ssid = config.ssid,
                radio = radioLine(config, status, backend),
                bandNote = bandNote(config, status, backend),
                engine = backend,
                upstream = status.upstream,
                devices = status.clients.size,
                busy = busy,
                // applied == null while running: started outside the app (or app data cleared) - offer to apply ours.
                pendingChanges = HotspotController.needsApply(applied, config, status, backend),
                canStart = problems.isEmpty() && backend != null,
                // Something may still be up after a failure: always allow cleaning up.
                canStop = status.state == EngineState.RUNNING || status.hostapdAlive || status.dhcpAlive || status.daemonAlive,
                tailscaleWanted = caps.tailscale && config.groups.any { it.tailscale },
                tailscaleIface = status.tailscale,
                exits = status.exits.map { e ->
                    val g = config.group(e.groupId)
                    val name = g?.name ?: e.groupId
                    val node = g?.takeIf { it.exitNodeIp == e.nodeIp }?.exitNodeName?.ifBlank { null } ?: e.nodeIp
                    when {
                        e.active -> "$name: internet via exit node $node" to true
                        e.backendState == "NeedsLogin" ->
                            "$name: Tailscale sign-in needed (edit the network) - normal internet for now" to false
                        e.backendState == "NeedsMachineAuth" ->
                            "$name: approve ${g?.tailscaleHostname ?: "its device"} in the Tailscale admin console - normal internet for now" to false
                        e.backendState == "Running" -> "$name: exit node $node unreachable - normal internet for now" to false
                        else -> "$name: connecting to Tailscale - normal internet for now" to false
                    }
                } + status.subnets.map { sn ->
                    val g = config.group(sn.groupId)
                    val name = g?.name ?: sn.groupId
                    when {
                        sn.approved -> "$name: shared with your tailnet (${sn.subnet})" to true
                        sn.backendState == "NeedsLogin" -> "$name: Tailscale sign-in needed to share ${sn.subnet} (edit the network)" to false
                        sn.backendState == "NeedsMachineAuth" ->
                            "$name: approve ${g?.tailscaleHostname ?: "its device"} in the Tailscale admin console to share ${sn.subnet}" to false
                        sn.backendState == "Running" ->
                            "$name: approve ${sn.subnet} for ${g?.tailscaleHostname ?: "its device"} in the Tailscale admin console" to false
                        else -> "$name: connecting to Tailscale to share ${sn.subnet}" to false
                    }
                },
                onStart = { HotspotController.startAsync() },
                onStop = { HotspotController.stopAsync() },
            )
        }

        error?.let { e ->
            item {
                MessageCard(title = "Problem", text = e, error = true, onDismiss = { HotspotController.clearError() })
            }
        }
        if (problems.isNotEmpty()) item {
            MessageCard(title = "Fix before starting", text = problems.joinToString("\n") { "• $it" }, error = true)
        }

        item { SectionHeader("Connected devices (${status.clients.size})") }
        if (status.clients.isEmpty()) item {
            Text(
                if (status.state == EngineState.RUNNING) "No devices connected yet." else "Start the hotspot to see devices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        items(status.clients, key = { it.mac }) { c ->
            val idx = config.groups.indexOfFirst { it.id == c.groupId }
            ClientRow(
                client = c,
                groupName = config.group(c.groupId)?.name ?: c.groupId,
                color = groupColor(idx),
                // Only a fixed IP in the network it joined applies to it.
                hasFixedIp = caps.fixedIps && config.staticLeases.any { it.mac.equals(c.mac, ignoreCase = true) && it.groupId == c.groupId },
                stats = caps.deviceStats,
                canFixIp = caps.fixedIps,
                canBlock = caps.blocking,
                onFixedIp = { fixedIpFor = c },
                onBlock = { blockFor = c },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    fixedIpFor?.let { c ->
        val existing = config.staticLeases.firstOrNull { it.mac.equals(c.mac, ignoreCase = true) }
        FixedIpDialog(
            config = config,
            clients = status.clients,
            // The device's network is the password it joined with now. A fixed IP it has in another network moves
            // here (one per device), starting from the address it has now - the old one can't fit this subnet.
            start = existing?.let { if (it.groupId == c.groupId) it else StaticLease(it.mac, c.groupId, c.ip ?: "", it.label) }
                ?: StaticLease(c.mac, c.groupId, c.ip ?: "", c.hostname ?: ""),
            editing = existing,
            lockDevice = true,
            onDismiss = { fixedIpFor = null },
            onSave = { lease ->
                HotspotController.updateConfig(config.copy(staticLeases = config.staticLeases.filterNot { it == existing } + lease))
                fixedIpFor = null
                scope.launch { snackbar.showSnackbar(savedMessage("Fixed IP saved", status.state)) }
            },
        )
    }
    blockFor?.let { c ->
        AlertDialog(
            onDismissRequest = { blockFor = null },
            title = { Text("Block ${c.hostname ?: c.mac}?") },
            text = { Text("The device won't be able to join any of your networks. Applies when the hotspot restarts (Apply changes). You can unblock it in the Fixed IPs tab.") },
            confirmButton = {
                TextButton(onClick = {
                    HotspotController.updateConfig(config.copy(blockedMacs = (config.blockedMacs + c.mac).distinct()))
                    blockFor = null
                    scope.launch { snackbar.showSnackbar(savedMessage("Blocked", status.state)) }
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { blockFor = null }) { Text("Cancel") } },
        )
    }
}

/** Band and channel: the hotspot's actual frequency while the phone's own hotspot runs, else the settings. */
fun radioLine(config: HotspotConfig, status: EngineStatus, backend: BackendKind?): String {
    val f = status.frequencyMhz
    return when {
        backend == BackendKind.SHIZUKU && status.state == EngineState.RUNNING && f > 0 ->
            if (f < 3000) "2.4 GHz channel ${(f - 2407) / 5} ($f MHz)" else "5 GHz channel ${(f - 5000) / 5} ($f MHz)"
        backend == BackendKind.SHIZUKU && config.band5 ->
            if (config.channel5 == 0) "5 GHz, channel chosen by the phone" else "5 GHz channel ${config.channel5}"
        else -> "2.4 GHz channel ${config.channel} (${channelFrequency(config.channel)} MHz)"
    }
}

/** 5 GHz was chosen but the phone's hotspot runs on 2.4 GHz: the phone decides when it can't (its own Wi-Fi, country). */
fun bandNote(config: HotspotConfig, status: EngineStatus, backend: BackendKind?): String? =
    if (backend == BackendKind.SHIZUKU && status.state == EngineState.RUNNING && config.band5 && status.frequencyMhz in 1..2999)
        "5 GHz isn't possible right now, so the phone uses 2.4 GHz. This usually happens while the phone itself is on Wi-Fi."
    else null

/** Snackbar text after a settings change: while the hotspot runs, changes wait for Apply. */
fun savedMessage(what: String, state: EngineState): String =
    if (state == EngineState.RUNNING) "$what - tap Apply changes" else what

/**
 * Shown under the title of the other tabs while the running hotspot uses older settings than the saved ones.
 */
@Composable
fun PendingChangesBar() {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val applied by HotspotController.applied.collectAsStateWithLifecycle()
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val busy by HotspotController.busy.collectAsStateWithLifecycle()
    val backend by HotspotController.backend.collectAsStateWithLifecycle()
    val pending = HotspotController.needsApply(applied, config, status, backend)
    if (!pending && busy == null) return
    val problems = remember(config, backend) { HotspotController.problems(config, backend) }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        busy != null -> busy!!
                        problems.isNotEmpty() -> "Settings changed, but some need fixing first (see Status)."
                        else -> "Settings changed. Applying restarts the hotspot."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (busy == null && problems.isEmpty() && backend != null) {
                    Button(onClick = { HotspotController.startAsync() }) { Text("Apply changes") }
                }
            }
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: EngineState,
    reason: String,
    ssid: String,
    radio: String,
    /** Why the hotspot runs on another band than chosen, if it does. */
    bandNote: String?,
    engine: BackendKind?,
    upstream: String,
    devices: Int,
    busy: String?,
    pendingChanges: Boolean,
    canStart: Boolean,
    canStop: Boolean,
    tailscaleWanted: Boolean,
    tailscaleIface: String,
    /** One line per network using its own Tailscale instance (exit node, shared subnet), and whether all is well. */
    exits: List<Pair<String, Boolean>>,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = state == EngineState.RUNNING
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (running) Icons.Default.WifiTethering else Icons.Default.WifiTetheringOff,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (state) {
                            EngineState.RUNNING -> "Hotspot is on"
                            EngineState.STARTING -> "Starting..."
                            EngineState.FAILED -> "Hotspot stopped (error)"
                            EngineState.UNKNOWN -> if (reason.isNotBlank()) "Hotspot state unknown" else "Hotspot is off"
                            else -> "Hotspot is off"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("$ssid · $radio", style = MaterialTheme.typography.bodyMedium)
                    if (bandNote != null) Text(bandNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    if (engine == BackendKind.SHIZUKU) Text(
                        "Shizuku engine · the phone's own hotspot",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (running) Text(
                        (if (upstream.isBlank()) "No internet source" else "Internet via $upstream") + " · $devices device${if (devices == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (running && tailscaleWanted) Text(
                        if (tailscaleIface.isNotBlank()) "Tailscale connected ($tailscaleIface)"
                        else "Tailscale not connected - open the Tailscale app and connect",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (tailscaleIface.isNotBlank()) Color.Unspecified else MaterialTheme.colorScheme.error,
                    )
                    if (running) exits.forEach { (text, ok) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ok) Color.Unspecified else MaterialTheme.colorScheme.error,
                        )
                    }
                    if ((state == EngineState.FAILED || state == EngineState.UNKNOWN) && reason.isNotBlank()) Text(
                        reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (busy != null) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(busy, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = onStop, enabled = busy == null) { Text("Stop") }
                    if (pendingChanges) Button(onClick = onStart, enabled = busy == null && canStart) { Text("Apply changes") }
                } else {
                    Button(onClick = onStart, enabled = busy == null && canStart) { Text("Start hotspot") }
                    if (canStop) OutlinedButton(onClick = onStop, enabled = busy == null) { Text("Stop / clean up") }
                }
            }
            if (pendingChanges) Text(
                "Settings changed. Applying restarts the hotspot; devices reconnect by themselves.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ClientRow(
    client: Client,
    groupName: String,
    color: Color,
    hasFixedIp: Boolean,
    /** Signal, traffic and link rate are known (root engine). */
    stats: Boolean,
    canFixIp: Boolean,
    canBlock: Boolean,
    onFixedIp: () -> Unit,
    onBlock: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val active = client.uploadRate + client.downloadRate > 1024
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Signal: animated bars + dBm
            if (stats) Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SignalIcon(rssi = client.rssi, active = active)
                Text(
                    client.rssi?.let { "$it dBm" } ?: "- dBm",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.width(if (stats) 10.dp else 4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(client.hostname ?: "Unknown device", style = MaterialTheme.typography.titleSmall)
                    if (hasFixedIp) Icon(Icons.Default.PushPin, contentDescription = "Fixed IP", modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("${client.ip ?: "getting address..."} · $groupName", style = MaterialTheme.typography.bodyMedium)
                }
                // Current traffic, from the device's point of view.
                if (stats) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 2.dp)) {
                    // No driver stats -> no download counter: show "–", not a misleading 0.
                    RateText(Icons.Default.ArrowDownward, "Download", client.downloadRate.takeIf { client.downloadBytes != null })
                    RateText(Icons.Default.ArrowUpward, "Upload", client.uploadRate)
                }
                Text(
                    listOf(
                        when {
                            !stats -> ""
                            client.downloadBytes != null -> "Used ${formatBytes(client.totalBytes)}"
                            else -> "Sent ${formatBytes(client.uploadBytes)}"
                        },
                        formatSince(client.connectedSinceEpoch),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    listOfNotNull(
                        client.mac,
                        if (client.linkDownKbps != null && client.linkUpKbps != null)
                            "link ${formatLinkRate(client.linkDownKbps)}/${formatLinkRate(client.linkUpKbps)} Mb/s" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Device actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (hasFixedIp) "Change fixed IP" else "Give fixed IP") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        enabled = canFixIp,
                        onClick = { menu = false; onFixedIp() },
                    )
                    DropdownMenuItem(
                        text = { Text("Block device") },
                        leadingIcon = { Icon(Icons.Default.Block, null) },
                        enabled = canBlock,
                        onClick = { menu = false; onBlock() },
                    )
                    if (!canFixIp || !canBlock) Text(
                        when {
                            !canFixIp && !canBlock -> "Fixed IPs need root; this phone's hotspot can't block devices"
                            !canFixIp -> "Fixed IPs need root"
                            else -> PHONE_CANT
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).width(220.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RateText(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bytesPerSecond: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(14.dp),
            tint = if ((bytesPerSecond ?: 0) > 1024) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            bytesPerSecond?.let { formatRate(it) } ?: "–",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (actionLabel != null) FilledTonalButton(onClick = onAction) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(actionLabel)
        }
    }
}

@Composable
fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

@Composable
fun MessageCard(title: String, text: String, error: Boolean, onDismiss: (() -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
