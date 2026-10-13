package com.oshri.multihotspot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.Validation

/** Tab 3: the networks (one password each). Editing opens [GroupEditScreen] over the tabs. */
@Composable
fun NetworksTab(padding: PaddingValues, onEditGroup: (String?) -> Unit) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val status by HotspotController.status.collectAsStateWithLifecycle()
    var qrFor by remember { mutableStateOf<Group?>(null) }
    val caps = capabilities()
    val canAdd = config.groups.size < Validation.MAX_GROUPS && caps.multipleNetworks

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                SectionHeader("One Wi-Fi name, one password each", actionLabel = if (canAdd) "Add" else null) { onEditGroup(null) }
                Text(
                    when {
                        !caps.multipleNetworks -> "With the Shizuku engine there is one network: devices join \"${config.ssid}\" " +
                            "with ${config.groups.first().name}'s password. The other networks need root."
                        canAdd -> "Devices joining \"${config.ssid}\" land in the network whose password they used."
                        else -> "Devices joining \"${config.ssid}\" land in the network whose password they used. " +
                            "You have the maximum of ${Validation.MAX_GROUPS} networks."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        items(config.groups, key = { it.id }) { g ->
            val idx = config.groups.indexOf(g)
            GroupCard(
                // Shizuku runs the first network only.
                modifier = Modifier.unavailable(if (idx > 0 && !caps.multipleNetworks) NEEDS_ROOT else null),
                rootDetails = caps.firewall,
                isolationShown = caps.deviceIsolation,
                group = g,
                color = groupColor(idx),
                connected = status.clients.count { it.groupId == g.id },
                strictIsolation = config.strictIsolation,
                reachNames = g.canReach.mapNotNull { id -> config.group(id)?.name },
                onEdit = { onEditGroup(g.id) },
                onQr = { qrFor = g },
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    qrFor?.let { g ->
        WifiQrDialog(ssid = config.ssid, networkName = g.name, password = g.password, hidden = config.hidden, onDismiss = { qrFor = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    modifier: Modifier,
    /** Addresses, firewall and Tailscale tags apply (root engine). */
    rootDetails: Boolean,
    isolationShown: Boolean,
    group: Group,
    color: Color,
    connected: Int,
    strictIsolation: Boolean,
    reachNames: List<String>,
    onEdit: () -> Unit,
    onQr: () -> Unit,
) {
    var showPw by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Card(modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(color, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                // Title row: name, password, then show / QR / copy, then edit.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Not weighted: the password takes all remaining space, so the buttons sit at the right edge.
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (showPw) group.password else "•".repeat(8),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CardIconButton(
                        if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        if (showPw) "Hide password" else "Show password",
                    ) { showPw = !showPw }
                    CardIconButton(Icons.Default.QrCode2, "Show QR code for ${group.name}", onQr)
                    CardIconButton(Icons.Default.ContentCopy, "Copy password") { clipboard.setText(AnnotatedString(group.password)) }
                    CardIconButton(Icons.Default.Edit, "Edit ${group.name}", onEdit)
                }
                Text(
                    if (rootDetails) "${group.networkCidr} · gateway ${group.gateway}" else "Addresses chosen by Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rootDetails) {
                        Tag(if (group.internet) "Internet" else "No internet")
                        if (group.tailscale) Tag("Tailscale")
                        if (group.usesExitNode) Tag("Exit node: ${group.exitNodeName.ifBlank { group.exitNodeIp }}")
                        if (group.shareSubnet) Tag("Shared with tailnet")
                        if (reachNames.isNotEmpty()) Tag("Can reach ${reachNames.joinToString(", ")}")
                    } else Tag("Internet")
                    if (isolationShown) Tag(
                        when {
                            rootDetails && !strictIsolation -> "Not isolated (strict isolation off)"
                            group.devicesSeeEachOther -> "Devices see each other"
                            else -> "Devices isolated"
                        }
                    )
                    if (connected > 0) Tag("$connected online")
                }
            }
        }
    }
}

/** Compact icon button so four fit next to the name and password on a phone-width card. */
@Composable
private fun CardIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
