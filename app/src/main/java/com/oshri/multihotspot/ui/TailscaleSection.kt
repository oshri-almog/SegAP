package com.oshri.multihotspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.engine.TailscalePackage
import com.oshri.multihotspot.model.ExitNodeOption
import com.oshri.multihotspot.model.TailscaleInstance
import kotlinx.coroutines.delay

private const val ADMIN_MACHINES = "https://login.tailscale.com/admin/machines"

/**
 * Network editor: this network's own Tailscale instance (a device named [hostname] in the tailnet, independent
 * of the Tailscale app on this phone). It can send the network's internet through an exit node of its own,
 * and/or share the network with the tailnet as a subnet route.
 */
@Composable
fun TailscaleSection(
    groupId: String,
    hostname: String,
    /** This network's subnet (e.g. 192.168.10.0/24). */
    subnet: String,
    internet: Boolean,
    exitEnabled: Boolean,
    onExitEnabledChange: (Boolean) -> Unit,
    selectedIp: String,
    selectedName: String,
    onSelect: (ExitNodeOption?) -> Unit,
    shareSubnet: Boolean,
    onShareSubnetChange: (Boolean) -> Unit,
    /** Called once this editor started the network's instance (so it can be stopped again when leaving). */
    onInstanceUsed: () -> Unit,
) {
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val setup by HotspotController.tailscaleSetup.collectAsStateWithLifecycle()
    val installed = status.tailscaleVersion.isNotBlank()
    var inst by remember { mutableStateOf<TailscaleInstance?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    SectionLabel("Tailscale for this network")
    SwitchRow(
        "Use a Tailscale exit node",
        "This network's internet goes out through a device in your tailnet (for example a router or server at home) " +
            "instead of this phone's connection. Other networks aren't affected.",
        exitEnabled,
    ) { onExitEnabledChange(it) }
    SwitchRow(
        "Share this network with your tailnet",
        "Devices in your tailnet can reach the devices on this network${if (subnet.isNotBlank()) " ($subnet)" else ""} " +
            "through \"$hostname\", as a Tailscale subnet route. Works without internet access too.",
        shareSubnet,
    ) { onShareSubnetChange(it) }
    if (!exitEnabled && !shareSubnet) return

    // While the editor is visible, keep the instance running and its state fresh. Not in the background: the user
    // may stop the hotspot from the notification meanwhile, and nothing should start the instance again then.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentHostname by rememberUpdatedState(hostname)
    val currentOnInstanceUsed by rememberUpdatedState(onInstanceUsed)
    LaunchedEffect(groupId, installed, lifecycleOwner) {
        if (!installed) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Before starting: the root call can't be cancelled, so the instance may be up even if we leave meanwhile.
            currentOnInstanceUsed()
            inst = HotspotController.tailscaleUp(groupId, currentHostname)
            while (true) {
                delay(if (inst?.running == true) 5_000 else 2_000)
                val s = HotspotController.tailscaleStatus(groupId)
                // The daemon may have stopped meanwhile (crash): start it again.
                inst = if (s.backendState == "down") HotspotController.tailscaleUp(groupId, currentHostname) else s
            }
        }
    }

    if (exitEnabled && !internet) Hint("Turn on Internet access above to use an exit node.", error = true)
    when {
        !TailscalePackage.supported() -> Hint("Needs an arm64 phone.", error = true)
        !installed -> InstallCard(setup)
        else -> InstanceCard(
            inst = inst,
            hostname = hostname,
            subnet = subnet,
            exitEnabled = exitEnabled,
            selectedIp = selectedIp,
            selectedName = selectedName,
            onSelect = onSelect,
            shareSubnet = shareSubnet,
            onShowQr = { showQr = true },
            onSignOut = { confirmSignOut = true },
        )
    }
    if (exitEnabled) Hint("While the exit node can't be reached, devices use this phone's normal internet connection.")
    if (shareSubnet) Hint(
        "Tailnet devices (as far as your tailnet's access rules allow) reach every device on this network, whatever its " +
            "isolation setting; their connections appear to come from the phone.",
    )

    // The sign-in QR only while a sign-in is pending; close it (and forget the request) once the login moved on.
    LaunchedEffect(inst?.needsLogin) { if (inst?.needsLogin != true) showQr = false }
    if (showQr && inst?.needsLogin == true) inst?.authUrl?.takeIf { it.isNotBlank() }?.let { url ->
        QrDialog(
            title = "Sign in $hostname",
            content = url,
            description = "QR code of the Tailscale sign-in link",
            caption = "Scan with a device where you can sign in to Tailscale, then approve \"$hostname\".",
            onDismiss = { showQr = false },
        )
    }
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out $hostname?") },
        text = { Text("This network's Tailscale instance leaves your tailnet; the exit node and sharing options are turned off. You can sign in again later.") },
        confirmButton = {
            TextButton(onClick = {
                confirmSignOut = false
                HotspotController.tailscaleLogoutAsync(groupId)
                inst = null
                onSelect(null)
                onExitEnabledChange(false)
                onShareSubnetChange(false)
            }) { Text("Sign out") }
        },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
private fun InstallCard(setup: HotspotController.TailscaleSetup?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Needs SegAP's own copy of Tailscale", style = MaterialTheme.typography.titleSmall)
            Text(
                "A one-time download of the official Tailscale ${TailscalePackage.VERSION} package for Linux " +
                    "(${(TailscalePackage.SIZE + 500_000) / 1_000_000} MB, from pkgs.tailscale.com, checked against its published checksum). " +
                    "Each network that uses it then appears in your tailnet as its own device.",
                style = MaterialTheme.typography.bodySmall,
            )
            when {
                setup == null -> Button(onClick = { HotspotController.installTailscaleAsync() }) { Text("Download and install") }
                setup.failed -> {
                    Text(setup.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { HotspotController.clearTailscaleSetupError(); HotspotController.installTailscaleAsync() }) { Text("Try again") }
                }
                else -> {
                    Text(setup.message, style = MaterialTheme.typography.bodySmall)
                    val p = setup.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(
    inst: TailscaleInstance?,
    hostname: String,
    subnet: String,
    exitEnabled: Boolean,
    selectedIp: String,
    selectedName: String,
    onSelect: (ExitNodeOption?) -> Unit,
    shareSubnet: Boolean,
    onShowQr: () -> Unit,
    onSignOut: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                inst == null || (!inst.running && !inst.needsLogin && !inst.needsApproval && inst.error.isBlank()) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Starting Tailscale for this network...", style = MaterialTheme.typography.bodyMedium)
                    }
                inst.error.isNotBlank() && !inst.running && !inst.needsLogin && !inst.needsApproval ->
                    Text(inst.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                inst.needsApproval -> {
                    Text("Waiting for approval", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Your tailnet requires new devices to be approved. Approve \"$hostname\" in the Tailscale admin console " +
                            "(Machines).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { uriHandler.openUri(ADMIN_MACHINES) }) { Text("Open admin console") }
                }
                inst.needsLogin -> {
                    Text("Sign in to add this network to your tailnet", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "It appears as the device \"$hostname\". Approving it in Tailscale's sign-in page is enough; " +
                            "the login is kept for next time.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (inst.authUrl.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { uriHandler.openUri(inst.authUrl) }) { Text("Sign in") }
                        OutlinedButton(onClick = onShowQr) { Text("QR code") }
                    } else Text("Getting a sign-in link...", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("Signed in as ${inst.selfName.ifBlank { hostname }} · ${inst.selfIp}", style = MaterialTheme.typography.titleSmall)
                    if (exitEnabled) ExitNodeList(inst, selectedIp, selectedName, onSelect)
                    if (shareSubnet) {
                        if (exitEnabled) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        Text("Subnet route", style = MaterialTheme.typography.labelLarge)
                        if (subnet.isNotBlank() && subnet in inst.approvedRoutes) {
                            Text("$subnet is approved: your tailnet can reach this network.", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(
                                "$hostname advertises $subnet once you save and apply. Approve it once in the Tailscale admin " +
                                    "console: Machines → $hostname → Edit route settings (or with autoApprovers in your policy).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { uriHandler.openUri(ADMIN_MACHINES) }) { Text("Open admin console") }
                        }
                    }
                    TextButton(onClick = onSignOut) { Text("Sign this network out of Tailscale") }
                }
            }
        }
    }
}

@Composable
private fun ExitNodeList(inst: TailscaleInstance, selectedIp: String, selectedName: String, onSelect: (ExitNodeOption?) -> Unit) {
    if (inst.exitNodes.isEmpty()) Text(
        "No exit nodes in your tailnet yet. On the device you want to use, run " +
            "\"tailscale set --advertise-exit-node\" (or enable it in its Tailscale app) and approve it in the admin console.",
        style = MaterialTheme.typography.bodySmall,
    ) else Text("Exit node", style = MaterialTheme.typography.labelLarge)
    inst.exitNodes.forEach { n ->
        Row(
            Modifier.fillMaxWidth().selectable(selected = n.ip == selectedIp, onClick = { onSelect(n) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = n.ip == selectedIp, onClick = { onSelect(n) })
            Column(Modifier.weight(1f)) {
                Text(n.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOf(n.ip, if (n.online) "online" else "offline", n.os).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (n.online) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    if (selectedIp.isNotBlank() && inst.exitNodes.none { it.ip == selectedIp }) Hint(
        "${selectedName.ifBlank { selectedIp }} isn't offered as an exit node any more - pick another one.",
        error = true,
    )
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
