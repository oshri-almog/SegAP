package com.oshri.multihotspot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.WifiTetheringOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.Client
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.Ipv4
import com.oshri.multihotspot.model.StaticLease
import com.oshri.multihotspot.model.Validation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onSettings: () -> Unit, onLog: () -> Unit, onEditGroup: (String?) -> Unit) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val applied by HotspotController.applied.collectAsStateWithLifecycle()
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val busy by HotspotController.busy.collectAsStateWithLifecycle()
    val error by HotspotController.error.collectAsStateWithLifecycle()
    val root by HotspotController.rootAvailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val problems = remember(config) { Validation.config(config) }

    var fixedIpFor by remember { mutableStateOf<Client?>(null) }
    var blockFor by remember { mutableStateOf<Client?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MultiHotspot") },
                actions = {
                    IconButton(onClick = onLog) { Icon(Icons.Default.Description, contentDescription = "Log") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (root == false) item {
                MessageCard(
                    title = "No root access",
                    text = "MultiHotspot needs root. On this phone Magisk only loads when booting through recovery: " +
                        "run `adb reboot recovery` (or use the Magisk key combo), then allow root for MultiHotspot.",
                    error = true,
                )
            }

            item {
                StatusCard(
                    state = status.state,
                    ssid = config.ssid,
                    channel = config.channel,
                    upstream = status.upstream,
                    devices = status.clients.size,
                    busy = busy,
                    // applied == null while running: started outside the app (or app data cleared) - offer to apply ours.
                    pendingChanges = status.state == EngineState.RUNNING && applied != config,
                    canStart = problems.isEmpty() && root != false,
                    onStart = { scope.launch { HotspotController.start() } },
                    onStop = { scope.launch { HotspotController.stop() } },
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

            item {
                SectionHeader("Networks · one name, one password each", actionLabel = "Add") {
                    if (config.groups.size < Validation.MAX_GROUPS) onEditGroup(null)
                }
            }
            items(config.groups, key = { it.id }) { g ->
                val idx = config.groups.indexOf(g)
                GroupCard(
                    group = g,
                    color = groupColor(idx),
                    connected = status.clients.count { it.groupId == g.id },
                    onEdit = { onEditGroup(g.id) },
                )
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
                    hasFixedIp = config.staticLeases.any { it.mac.equals(c.mac, ignoreCase = true) },
                    onFixedIp = { fixedIpFor = c },
                    onBlock = { blockFor = c },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    fixedIpFor?.let { c ->
        val group = config.group(c.groupId)
        val existing = config.staticLeases.firstOrNull { it.mac.equals(c.mac, ignoreCase = true) }
        FixedIpDialog(
            mac = c.mac,
            group = group,
            initialIp = existing?.ip ?: c.ip ?: "",
            initialLabel = existing?.label ?: (c.hostname ?: ""),
            onDismiss = { fixedIpFor = null },
            onSave = { ip, label ->
                val others = config.staticLeases.filterNot { it.mac.equals(c.mac, ignoreCase = true) }
                HotspotController.updateConfig(config.copy(staticLeases = others + StaticLease(c.mac, c.groupId, ip, label)))
                fixedIpFor = null
                scope.launch { snackbar.showSnackbar("Fixed IP saved - tap Apply changes") }
            },
        )
    }
    blockFor?.let { c ->
        AlertDialog(
            onDismissRequest = { blockFor = null },
            title = { Text("Block ${c.hostname ?: c.mac}?") },
            text = { Text("The device won't be able to join any of your networks. Applies when the hotspot restarts (Apply changes). You can unblock it in Settings.") },
            confirmButton = {
                TextButton(onClick = {
                    HotspotController.updateConfig(config.copy(blockedMacs = (config.blockedMacs + c.mac).distinct()))
                    blockFor = null
                    scope.launch { snackbar.showSnackbar("Blocked - tap Apply changes") }
                }) { Text("Block") }
            },
            dismissButton = { TextButton(onClick = { blockFor = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusCard(
    state: EngineState,
    ssid: String,
    channel: Int,
    upstream: String,
    devices: Int,
    busy: String?,
    pendingChanges: Boolean,
    canStart: Boolean,
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
                            else -> "Hotspot is off"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        "$ssid · 2.4 GHz channel $channel (${channelFrequency(channel)} MHz)",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (running) Text(
                        (if (upstream.isBlank()) "No internet source" else "Internet via $upstream") + " · $devices device${if (devices == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
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
private fun GroupCard(group: Group, color: Color, connected: Int, onEdit: () -> Unit) {
    var showPw by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit ${group.name}") }
                }
                Text(
                    "${group.networkCidr} · gateway ${group.gateway}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (showPw) group.password else "•".repeat(10),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Show password")
                    }
                    IconButton(onClick = { clipboard.setText(AnnotatedString(group.password)) }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy password")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(if (group.internet) "Internet" else "No internet")
                    Tag(if (group.devicesSeeEachOther) "Devices see each other" else "Devices isolated")
                    if (connected > 0) Tag("$connected online")
                }
            }
        }
    }
}

@Composable
private fun ClientRow(
    client: Client,
    groupName: String,
    color: Color,
    hasFixedIp: Boolean,
    onFixedIp: () -> Unit,
    onBlock: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(client.hostname ?: "Unknown device", style = MaterialTheme.typography.titleSmall)
                    if (hasFixedIp) Icon(Icons.Default.PushPin, contentDescription = "Fixed IP", modifier = Modifier.padding(start = 4.dp).size(14.dp))
                }
                Text(
                    "${client.ip ?: "getting address..."} · $groupName",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    listOf(client.mac, formatSince(client.connectedSinceEpoch), "sent ${formatBytes(client.uploadBytes)}")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Device actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (hasFixedIp) "Change fixed IP" else "Give fixed IP") },
                        leadingIcon = { Icon(Icons.Default.PushPin, null) },
                        onClick = { menu = false; onFixedIp() },
                    )
                    DropdownMenuItem(
                        text = { Text("Block device") },
                        leadingIcon = { Icon(Icons.Default.Block, null) },
                        onClick = { menu = false; onBlock() },
                    )
                }
            }
        }
    }
}

@Composable
private fun FixedIpDialog(
    mac: String,
    group: Group?,
    initialIp: String,
    initialLabel: String,
    onDismiss: () -> Unit,
    onSave: (ip: String, label: String) -> Unit,
) {
    var ip by remember { mutableStateOf(initialIp) }
    var label by remember { mutableStateOf(initialLabel) }
    val err = when {
        group == null -> "This device's network no longer exists"
        Ipv4.parse(ip) == null -> "Not a valid IPv4 address"
        !Ipv4.inSubnet(Ipv4.parse(ip)!!, Ipv4.parse(group.gateway) ?: 0, group.prefix) -> "Must be inside ${group.networkCidr}"
        ip == group.gateway -> "That's the gateway address"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fixed IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$mac always gets this address in ${group?.name ?: "?"}.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = ip, onValueChange = { ip = it.trim() }, label = { Text("IP address") }, isError = err != null, singleLine = true,
                    supportingText = { err?.let { Text(it) } })
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ip, label) }, enabled = err == null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
