package com.oshri.multihotspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.Ipv4
import com.oshri.multihotspot.model.Mac
import com.oshri.multihotspot.model.Passwords
import com.oshri.multihotspot.model.StaticLease
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.suggestNewGroup

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupEditScreen(groupId: String?, onDone: () -> Unit) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val original = remember { groupId?.let { config.group(it) } ?: config.suggestNewGroup("Network ${config.groups.size + 1}") }
    val isNew = groupId == null

    var name by remember { mutableStateOf(original.name) }
    var password by remember { mutableStateOf(original.password) }
    var showPw by remember { mutableStateOf(isNew) }
    var gateway by remember { mutableStateOf(original.gateway) }
    var prefix by remember { mutableStateOf(original.prefix.toString()) }
    var dhcpStart by remember { mutableStateOf(original.dhcpStart) }
    var dhcpEnd by remember { mutableStateOf(original.dhcpEnd) }
    var lease by remember { mutableStateOf(original.leaseHours.toString()) }
    var internet by remember { mutableStateOf(original.internet) }
    var seeEachOther by remember { mutableStateOf(original.devicesSeeEachOther) }
    var tailscale by remember { mutableStateOf(original.tailscale) }
    var reach by remember { mutableStateOf(original.canReach.toSet()) }
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val caps = capabilities()
    var dns1 by remember { mutableStateOf(original.dns1) }
    var dns2 by remember { mutableStateOf(original.dns2) }
    var leases by remember { mutableStateOf(config.staticLeases.filter { it.groupId == original.id }) }
    var useExit by remember { mutableStateOf(original.usesExitNode) }
    var exitIp by remember { mutableStateOf(original.exitNodeIp) }
    var exitName by remember { mutableStateOf(original.exitNodeName) }
    var shareSubnet by remember { mutableStateOf(original.shareSubnet) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var addLease by remember { mutableStateOf(false) }

    // The exit-node section starts this network's Tailscale instance. When leaving: a new network that was never
    // saved signs it out again (it would be a stray device in the tailnet); otherwise it's stopped, unless the
    // running hotspot uses it. Its login is kept.
    var instanceUsed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            if (instanceUsed) {
                if (isNew && !saved) HotspotController.tailscaleLogoutAsync(original.id)
                else HotspotController.tailscaleReleaseAsync(original.id)
            }
        }
    }

    /** Keeps the DHCP range inside the subnet when the gateway or prefix changes. */
    fun syncRange(gw: String, pfx: String) {
        val g = Ipv4.parse(gw) ?: return
        val p = pfx.toIntOrNull()?.takeIf { it in 16..30 } ?: return
        val s = Ipv4.parse(dhcpStart); val e = Ipv4.parse(dhcpEnd)
        if (s != null && e != null && Ipv4.inSubnet(s, g, p) && Ipv4.inSubnet(e, g, p) && g !in s..e) return
        val net = Ipv4.network(g, p); val bc = Ipv4.broadcast(g, p)
        val size = bc - net - 1
        val (start, end) = if (size >= 200) Pair(net + 100, minOf(net + 200, bc - 1)) else Pair(net + 2, bc - 1)
        val (fs, fe) = if (g in start..end) Pair(if (g + 1 <= end) g + 1 else start, end) else Pair(start, end)
        dhcpStart = Ipv4.format(fs); dhcpEnd = Ipv4.format(fe)
    }

    fun build(): Group = original.copy(
        name = name.trim(), password = password, gateway = gateway.trim(),
        prefix = prefix.toIntOrNull() ?: -1, dhcpStart = dhcpStart.trim(), dhcpEnd = dhcpEnd.trim(),
        leaseHours = lease.toIntOrNull() ?: -1, internet = internet, devicesSeeEachOther = seeEachOther,
        dns1 = dns1.trim(), dns2 = dns2.trim(), tailscale = tailscale,
        // Keep the user's order of networks, drop ones that were deleted meanwhile.
        canReach = config.groups.map { it.id }.filter { it in reach && it != original.id },
        exitNodeIp = if (useExit) exitIp else "",
        exitNodeName = if (useExit) exitName else "",
        shareSubnet = shareSubnet,
    )

    fun save() {
        val g = build()
        if (useExit && exitIp.isBlank()) {
            errors = listOf("Pick an exit node, or turn off \"Use a Tailscale exit node\"")
            return
        }
        val groupErrors = Validation.group(g)
        if (groupErrors.isNotEmpty()) { errors = groupErrors; return }
        val groups = if (isNew) config.groups + g else config.groups.map { if (it.id == g.id) g else it }
        val oldLeases = config.staticLeases.filter { it.groupId == g.id }
        val candidate = config.copy(
            groups = groups,
            staticLeases = if (leases == oldLeases) config.staticLeases else config.staticLeases.filter { it.groupId != g.id } + leases,
        )
        val all = Validation.config(candidate).filter { it.contains(g.name) || it.startsWith("Fixed IP") || it.startsWith("Device") }
        if (all.isNotEmpty()) { errors = all; return }
        HotspotController.updateConfig(candidate)
        saved = true
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New network" else "Edit ${original.name}") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (!isNew && config.groups.size > 1) IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete network")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (errors.isNotEmpty()) MessageCard("Please fix", errors.joinToString("\n") { "• $it" }, error = true)

            OutlinedTextField(name, { name = it }, label = { Text("Name (only shown in this app)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Wi-Fi password for this network") },
                singleLine = true,
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                supportingText = { Text("Devices that join with this password land in this network. 8-63 characters, unique per network.") },
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showPw = !showPw }) { Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show") }
                        IconButton(onClick = { password = Passwords.generate(); showPw = true }) { Icon(Icons.Default.Autorenew, "Generate") }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (!caps.addressing) Text(
                "With the Shizuku engine, devices use this network's password; Android chooses the addresses. " +
                    "The settings below that need root are kept for when root is available.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Gated(caps.addressing) {
                SectionLabel("Addressing")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(gateway, { gateway = it; syncRange(it, prefix) }, label = { Text("Gateway (phone) IP") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(2f))
                    OutlinedTextField(prefix, { prefix = it.filter(Char::isDigit).take(2); syncRange(gateway, prefix) }, label = { Text("Prefix /") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                val cidr = build().networkCidr
                if (cidr.isNotEmpty()) Text("Subnet $cidr · mask ${Ipv4.format(Ipv4.mask(prefix.toIntOrNull()?.coerceIn(0, 32) ?: 24))}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dhcpStart, { dhcpStart = it }, label = { Text("DHCP from") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(dhcpEnd, { dhcpEnd = it }, label = { Text("DHCP to") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(lease, { lease = it.filter(Char::isDigit).take(3) }, label = { Text("Lease time (hours)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dns1, { dns1 = it }, label = { Text("DNS 1") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    OutlinedTextField(dns2, { dns2 = it }, label = { Text("DNS 2 (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
            }

            SectionLabel("Access")
            Gated(caps.firewall) {
                SwitchRow("Internet access", "Off = devices only reach the phone (LAN-only network, e.g. for IoT).", internet) { internet = it }
            }
            Gated(caps.deviceIsolation, phoneReason(rootHas = true)) {
                SwitchRow(
                    "Devices can see each other",
                    "Off = every device is isolated from the others in this network (recommended for guests).",
                    seeEachOther,
                ) { seeEachOther = it }
            }
            if (caps.firewall) Text(
                if (config.strictIsolation) "Devices in different networks can never reach each other."
                else "Strict isolation is OFF in Settings: all devices on all passwords can reach each other directly, and this switch has no effect.",
                style = MaterialTheme.typography.bodySmall,
                color = if (config.strictIsolation) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Gated(caps.tailscale) {
                SwitchRow(
                    "Access to Tailscale network",
                    "Devices can reach your tailnet (100.x addresses and its subnet routes) through this phone, as if they were the phone. " +
                        "Requires the Tailscale app on this phone to be signed in and connected.",
                    tailscale,
                ) { tailscale = it }
                if (tailscale) {
                    Text(
                        when {
                            status.tailscale.isNotBlank() -> "Tailscale is connected on this phone (${status.tailscale})."
                            else -> "Tailscale isn't connected right now (or the hotspot is off). Open the Tailscale app, sign in and connect."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dns1 != MAGIC_DNS) TextButton(onClick = { dns2 = if (dns1 != MAGIC_DNS) dns1 else dns2; dns1 = MAGIC_DNS }) {
                        Text("Use Tailscale DNS (MagicDNS) so tailnet names resolve")
                    }
                }
            }

            // Without root there are no Tailscale instances: don't start (or poll) one, just show the saved choices.
            if (!caps.tailscale) Gated(false) {
                SectionLabel("Tailscale exit node and sharing")
                SwitchRow("Use a Tailscale exit node", "This network's internet goes out through a device in your tailnet.", useExit) {}
                SwitchRow("Share this network with your tailnet", "Tailnet devices can reach this network's devices.", shareSubnet) {}
            } else TailscaleSection(
                groupId = original.id,
                hostname = build().tailscaleHostname,
                subnet = build().networkCidr,
                internet = internet,
                exitEnabled = useExit,
                onExitEnabledChange = { useExit = it },
                selectedIp = exitIp,
                selectedName = exitName,
                onSelect = { n -> exitIp = n?.ip ?: ""; exitName = n?.name ?: "" },
                shareSubnet = shareSubnet,
                onShareSubnetChange = { shareSubnet = it },
                onInstanceUsed = { instanceUsed = true },
            )

            Gated(caps.firewall) {
                SectionLabel("Access to other networks")
                val others = config.groups.filter { it.id != original.id }
                if (others.isEmpty()) {
                    Text("Add another network to allow access between them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "Devices in this network can open connections to devices in the selected networks " +
                            "(e.g. Home -> IoT to control smart devices). One-way: those devices can only answer, " +
                            "not start connections back, unless you allow it in their settings too.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.forEach { o ->
                            FilterChip(
                                selected = o.id in reach,
                                onClick = { reach = if (o.id in reach) reach - o.id else reach + o.id },
                                label = { Text("${o.name} · ${o.networkCidr}") },
                            )
                        }
                    }
                    val back = others.filter { original.id in it.canReach }.map { it.name }
                    if (back.isNotEmpty()) Text(
                        "Can be reached from: ${back.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Gated(caps.fixedIps) {
                SectionLabel("Fixed IPs in this network")
                if (leases.isEmpty()) Text("None. You can also add one from the Status or Fixed IPs tab.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                leases.forEach { l ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.ip, style = MaterialTheme.typography.titleSmall)
                                Text(listOf(l.label, l.mac).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { leases = leases - l }) { Icon(Icons.Default.Delete, "Remove fixed IP") }
                        }
                    }
                }
                OutlinedButton(onClick = { addLease = true }) { Text("Add fixed IP") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save() }) { Text("Save") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${original.name}?") },
        text = { Text("Its password stops working and its fixed IPs are removed (after Apply changes).") },
        confirmButton = {
            TextButton(onClick = {
                HotspotController.updateConfig(
                    config.copy(
                        groups = config.groups.filter { it.id != original.id }
                            .map { it.copy(canReach = it.canReach - original.id) },
                        staticLeases = config.staticLeases.filter { it.groupId != original.id },
                    )
                )
                // Its Tailscale instance (if it ever had one) leaves the tailnet.
                HotspotController.tailscaleLogoutAsync(original.id)
                instanceUsed = false
                confirmDelete = false
                onDone()
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )

    if (addLease) {
        var mac by remember { mutableStateOf("") }
        var ip by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        val g = build()
        val gw = Ipv4.parse(g.gateway)
        val err = when {
            Mac.normalize(mac) == null -> "MAC must look like aa:bb:cc:dd:ee:ff"
            Ipv4.parse(ip) == null -> "Not a valid IPv4 address"
            gw == null || !Ipv4.inSubnet(Ipv4.parse(ip)!!, gw, g.prefix) -> "Must be inside ${g.networkCidr}"
            ip == g.gateway -> "That's the gateway address"
            else -> null
        }
        AlertDialog(
            onDismissRequest = { addLease = false },
            title = { Text("Add fixed IP") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mac, { mac = it }, label = { Text("Device MAC") }, singleLine = true)
                    OutlinedTextField(ip, { ip = it.trim() }, label = { Text("IP address") }, singleLine = true,
                        isError = ip.isNotEmpty() && err != null, supportingText = { if (mac.isNotEmpty() || ip.isNotEmpty()) err?.let { Text(it) } })
                    OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                    Text("Tip: phones use a random MAC per network - copy it from the device list after it connects once.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = err == null, onClick = {
                    val m = Mac.normalize(mac)!!
                    leases = leases.filterNot { it.mac == m } + StaticLease(m, original.id, ip, label.trim())
                    addLease = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addLease = false }) { Text("Cancel") } },
        )
    }
}

private const val MAGIC_DNS = "100.100.100.100"

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
