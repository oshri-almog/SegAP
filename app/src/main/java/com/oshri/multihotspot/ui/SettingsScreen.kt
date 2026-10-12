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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.Mac
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.WifiMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    var ssid by remember { mutableStateOf(config.ssid) }
    var channel by remember { mutableStateOf(config.channel) }
    var hidden by remember { mutableStateOf(config.hidden) }
    var maxClients by remember { mutableFloatStateOf(config.maxClients.toFloat()) }
    var wifiMode by remember { mutableStateOf(config.wifiMode) }
    var iface by remember { mutableStateOf(config.iface) }
    var strict by remember { mutableStateOf(config.strictIsolation) }
    var bssid by remember { mutableStateOf(config.bssid) }
    var blocked by remember { mutableStateOf(config.blockedMacs) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }

    fun save() {
        val e = mutableListOf<String>()
        Validation.ssid(ssid)?.let { e += it }
        if (iface == "wlan0" && wifiMode != WifiMode.OFF) e += "Using wlan0 for the hotspot requires turning the phone's Wi-Fi off"
        if (e.isNotEmpty()) { errors = e; return }
        HotspotController.updateConfig(
            config.copy(
                ssid = ssid, channel = channel, hidden = hidden, maxClients = maxClients.toInt(),
                wifiMode = wifiMode, iface = iface, strictIsolation = strict, bssid = bssid, blockedMacs = blocked,
            )
        )
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hotspot settings") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (errors.isNotEmpty()) MessageCard("Please fix", errors.joinToString("\n") { "• $it" }, error = true)

            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it }, label = { Text("Wi-Fi name (SSID)") }, singleLine = true,
                supportingText = { Text("${ssid.toByteArray().size}/32 bytes") }, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Hidden network", "Don't broadcast the name; devices must type it in.", hidden) { hidden = it }

            SectionLabel("Channel (2.4 GHz)")
            Text(
                "Channel $channel · ${channelFrequency(channel)} MHz. 1, 6 and 11 don't overlap each other. " +
                    "5 GHz isn't available: the phone's Wi-Fi firmware doesn't allow a 5 GHz hotspot in your country.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HotspotConfig.CHANNELS_24GHZ.forEach { ch ->
                    FilterChip(selected = ch == channel, onClick = { channel = ch }, label = { Text("$ch") })
                }
            }

            SectionLabel("Max devices: ${maxClients.toInt()}")
            Slider(
                value = maxClients, onValueChange = { maxClients = it },
                valueRange = 1f..Validation.MAX_CLIENTS.toFloat(), steps = Validation.MAX_CLIENTS - 2,
            )

            SectionLabel("Phone's own Wi-Fi while the hotspot runs")
            RadioRow("Keep as is", "If the phone is on Wi-Fi, the hotspot shares that connection (repeater). Otherwise mobile data.",
                wifiMode == WifiMode.KEEP) { wifiMode = WifiMode.KEEP }
            RadioRow("Turn Wi-Fi off", "Always share mobile data. Wi-Fi is turned back on when the hotspot stops.",
                wifiMode == WifiMode.OFF) { wifiMode = WifiMode.OFF }

            SectionLabel("Isolation")
            SwitchRow(
                "Strict isolation (recommended)",
                "All traffic between devices passes through the phone's firewall. Turn off only if you need casting / device discovery between devices; " +
                    "then devices on the same Wi-Fi can reach each other directly.",
                strict,
            ) { strict = it }

            SectionLabel("Blocked devices")
            if (blocked.isEmpty()) Text("None. Block a device from the connected devices list.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            blocked.forEach { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        IconButton(onClick = { blocked = blocked - m }) { Icon(Icons.Default.Delete, "Unblock") }
                    }
                }
            }

            SectionLabel("Advanced")
            Text("Access point MAC (BSSID): $bssid", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { bssid = Mac.randomLocal() }) { Text("New random BSSID") }
            Text("Radio interface", style = MaterialTheme.typography.bodyLarge)
            RadioRow("swlan0 (recommended)", "Works alongside the phone's own Wi-Fi connection.", iface == "swlan0") { iface = "swlan0" }
            RadioRow("wlan0", "Only with the phone's Wi-Fi turned off.", iface == "wlan0") { iface = "wlan0"; wifiMode = WifiMode.OFF }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save() }) { Text("Save") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
            Text("Changes take effect when you tap Apply changes (or next start).", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RadioRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
