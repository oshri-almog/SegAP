package com.oshri.multihotspot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.Client
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.Ipv4
import com.oshri.multihotspot.model.Mac
import com.oshri.multihotspot.model.StaticLease
import kotlinx.coroutines.launch

/** A fixed-IP dialog request: [start] = initial values, [editing] = the lease it replaces (null = new). */
private data class LeaseEdit(val start: StaticLease, val editing: StaticLease?)

/** Tab 4: every fixed IP (all networks) and every blocked device. */
@Composable
fun FixedIpsTab(padding: PaddingValues) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var leaseEdit by remember { mutableStateOf<LeaseEdit?>(null) }
    var blocking by remember { mutableStateOf(false) }
    val caps = capabilities()
    val leaseReason = if (caps.fixedIps) null else NEEDS_ROOT
    val blockReason = if (caps.blocking) null else phoneReason(rootHas = true)

    val order = config.groups.map { it.id }
    // Network order, then address; leases of deleted networks last (Validation reports them).
    val leases = config.staticLeases.sortedWith(
        compareBy<StaticLease>({ order.indexOf(it.groupId).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { Ipv4.parse(it.ip) ?: Long.MAX_VALUE })
    )
    fun nameOf(mac: String): String? =
        config.staticLeases.firstOrNull { it.mac.equals(mac, ignoreCase = true) }?.label?.takeIf { it.isNotBlank() }
            ?: status.clients.firstOrNull { it.mac.equals(mac, ignoreCase = true) }?.hostname

    fun removeLease(l: StaticLease) {
        HotspotController.updateConfig(config.copy(staticLeases = config.staticLeases - l))
        scope.launch {
            val r = snackbar.showSnackbar(savedMessage("Fixed IP ${l.ip} removed", status.state), actionLabel = "Undo", duration = SnackbarDuration.Short)
            val now = HotspotController.config.value
            if (r == SnackbarResult.ActionPerformed && l !in now.staticLeases) HotspotController.updateConfig(now.copy(staticLeases = now.staticLeases + l))
        }
    }

    fun unblock(mac: String) {
        HotspotController.updateConfig(config.copy(blockedMacs = config.blockedMacs - mac))
        scope.launch {
            val r = snackbar.showSnackbar(savedMessage("Unblocked $mac", status.state), actionLabel = "Undo", duration = SnackbarDuration.Short)
            val now = HotspotController.config.value
            if (r == SnackbarResult.ActionPerformed && mac !in now.blockedMacs) HotspotController.updateConfig(now.copy(blockedMacs = now.blockedMacs + mac))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                SectionHeader("Fixed IPs (${leases.size})", actionLabel = if (caps.fixedIps) "Add" else null) {
                    leaseEdit = LeaseEdit(StaticLease("", config.groups.firstOrNull()?.id ?: "", "", ""), editing = null)
                }
                leaseReason?.let { UnavailableNote(it, Modifier.padding(top = 4.dp)) }
                Hint("A device with a fixed IP always gets the same address in its network. Phones use a different random MAC " +
                    "for each Wi-Fi name, so pick the device from the connected list after it joins once.")
            }
        }
        if (leases.isEmpty()) item { Hint("None yet.") }
        // No item keys: a hand-edited config could hold duplicates, and duplicate keys crash the list.
        items(leases) { l ->
            val idx = order.indexOf(l.groupId)
            LeaseRow(
                modifier = Modifier.unavailable(leaseReason),
                lease = l,
                networkName = config.group(l.groupId)?.name ?: "deleted network",
                color = if (idx < 0) MaterialTheme.colorScheme.error else groupColor(idx),
                // The device may be connected to another network (same MAC, other password): it doesn't get this address there.
                onlineNote = status.clients.firstOrNull { it.mac.equals(l.mac, ignoreCase = true) }?.let { c ->
                    if (c.groupId == l.groupId) "online now" else "online in ${config.group(c.groupId)?.name ?: c.groupId} now"
                },
                onEdit = { leaseEdit = LeaseEdit(l, editing = l) },
                onRemove = { removeLease(l) },
            )
        }

        item {
            Column(Modifier.padding(top = 8.dp)) {
                SectionHeader("Blocked devices (${config.blockedMacs.size})", actionLabel = if (caps.blocking) "Block" else null) { blocking = true }
                blockReason?.let { UnavailableNote(it, Modifier.padding(top = 4.dp)) }
                Hint("Blocked devices can't join any of your networks, whatever password they use.")
            }
        }
        if (config.blockedMacs.isEmpty()) item { Hint("None.") }
        items(config.blockedMacs) { m ->
            Card(Modifier.unavailable(blockReason).fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        nameOf(m)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        Text(m, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { unblock(m) }) { Icon(Icons.Default.Delete, contentDescription = "Unblock $m") }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    leaseEdit?.let { e ->
        FixedIpDialog(
            config = config,
            clients = status.clients,
            start = e.start,
            editing = e.editing,
            lockDevice = false,
            onDismiss = { leaseEdit = null },
            onSave = { lease ->
                HotspotController.updateConfig(config.copy(staticLeases = config.staticLeases.filterNot { it == e.editing } + lease))
                leaseEdit = null
                scope.launch { snackbar.showSnackbar(savedMessage("Fixed IP saved", status.state)) }
            },
        )
    }
    if (blocking) BlockDialog(
        config = config,
        clients = status.clients,
        onDismiss = { blocking = false },
        onBlock = { mac ->
            HotspotController.updateConfig(config.copy(blockedMacs = (config.blockedMacs + mac).distinct()))
            blocking = false
            scope.launch { snackbar.showSnackbar(savedMessage("Blocked $mac", status.state)) }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LeaseRow(modifier: Modifier, lease: StaticLease, networkName: String, color: Color, onlineNote: String?, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOf(lease.ip, lease.label).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(lease.mac, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(networkName, onlineNote).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "Edit fixed IP ${lease.ip}") }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, contentDescription = "Remove fixed IP ${lease.ip}") }
        }
    }
}

/**
 * Add or edit a fixed IP. [lockDevice]: opened for one connected device, so its MAC and network can't change
 * (its network is the password it joined with).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FixedIpDialog(
    config: HotspotConfig,
    clients: List<Client>,
    start: StaticLease,
    editing: StaticLease?,
    lockDevice: Boolean,
    onDismiss: () -> Unit,
    onSave: (StaticLease) -> Unit,
) {
    var groupId by remember { mutableStateOf(start.groupId) }
    var mac by remember { mutableStateOf(start.mac) }
    var ip by remember { mutableStateOf(start.ip) }
    var label by remember { mutableStateOf(start.label) }

    val group = config.group(groupId)
    val gw = group?.let { Ipv4.parse(it.gateway) }
    val m = Mac.normalize(mac)
    val addr = Ipv4.parse(ip)
    val others = config.staticLeases.filterNot { it == editing }
    val err = when {
        group == null || gw == null -> "Pick a network"
        m == null -> "MAC must look like aa:bb:cc:dd:ee:ff"
        addr == null -> "Not a valid IPv4 address"
        !Ipv4.inSubnet(addr, gw, group.prefix) -> "Must be inside ${group.networkCidr}"
        addr == gw -> "That's the gateway address"
        addr == Ipv4.network(gw, group.prefix) || addr == Ipv4.broadcast(gw, group.prefix) -> "That's the network or broadcast address"
        others.any { it.ip == ip } -> "$ip is already fixed for another device"
        others.any { Mac.normalize(it.mac) == m } -> "This device already has a fixed IP"
        else -> null
    }
    // Connected devices that don't have a fixed IP yet: one tap fills MAC, network, address and name.
    val pickable = if (lockDevice || editing != null) emptyList() else
        clients.filter { c -> config.staticLeases.none { it.mac.equals(c.mac, ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Add fixed IP" else "Edit fixed IP") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pickable.isNotEmpty()) {
                    Text("Connected now", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pickable.forEach { c ->
                            FilterChip(
                                selected = Mac.normalize(c.mac) == m,
                                onClick = {
                                    mac = c.mac; groupId = c.groupId
                                    c.ip?.let { ip = it }
                                    if (label.isBlank()) label = c.hostname ?: ""
                                },
                                label = { Text(c.hostname ?: c.mac) },
                            )
                        }
                    }
                }
                if (lockDevice) {
                    Text("${start.mac} always gets this address in ${group?.name ?: "?"}.", style = MaterialTheme.typography.bodyMedium)
                    if (editing != null && editing.groupId != start.groupId) Text(
                        "This replaces its fixed IP ${editing.ip} in ${config.group(editing.groupId)?.name ?: "another network"} (one fixed IP per device).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Network", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        config.groups.forEachIndexed { i, g ->
                            FilterChip(
                                selected = g.id == groupId,
                                onClick = { groupId = g.id },
                                label = { Text(g.name) },
                                leadingIcon = { Box(Modifier.size(8.dp).background(groupColor(i), CircleShape)) },
                            )
                        }
                    }
                    OutlinedTextField(mac, { mac = it.trim() }, label = { Text("Device MAC") }, singleLine = true)
                }
                OutlinedTextField(
                    ip, { ip = it.trim() }, label = { Text("IP address") }, singleLine = true,
                    placeholder = { group?.let { Text("in ${it.networkCidr}") } },
                )
                OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                if (err != null && (mac.isNotEmpty() || ip.isNotEmpty())) {
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = err == null, onClick = { onSave(StaticLease(m!!, groupId, ip, label.trim())) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockDialog(config: HotspotConfig, clients: List<Client>, onDismiss: () -> Unit, onBlock: (String) -> Unit) {
    var mac by remember { mutableStateOf("") }
    val m = Mac.normalize(mac)
    val err = when {
        m == null -> "MAC must look like aa:bb:cc:dd:ee:ff"
        config.blockedMacs.any { Mac.normalize(it) == m } -> "Already blocked"
        else -> null
    }
    val pickable = clients.filter { c -> config.blockedMacs.none { it.equals(c.mac, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block a device") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pickable.isNotEmpty()) {
                    Text("Connected now", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pickable.forEach { c ->
                            FilterChip(selected = Mac.normalize(c.mac) == m, onClick = { mac = c.mac }, label = { Text(c.hostname ?: c.mac) })
                        }
                    }
                }
                OutlinedTextField(mac, { mac = it.trim() }, label = { Text("Device MAC") }, singleLine = true)
                if (err != null && mac.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Applies when the hotspot restarts (Apply changes).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = err == null, onClick = { onBlock(m!!) }) { Text("Block") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
