package com.oshri.multihotspot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.engine.BackendKind
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.Mac
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.WifiMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Tab 2: settings shared by all networks (Wi-Fi name, channel, radio). Edits are kept until Save or Discard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HotspotTab(padding: PaddingValues) {
    val config by HotspotController.config.collectAsStateWithLifecycle()
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val backend by HotspotController.backend.collectAsStateWithLifecycle()
    val caps = capabilities()
    val shizuku = backend == BackendKind.SHIZUKU
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var ssid by rememberSaveable { mutableStateOf(config.ssid) }
    var channel by rememberSaveable { mutableIntStateOf(config.channel) }
    var band5 by rememberSaveable { mutableStateOf(config.band5) }
    var channel5 by rememberSaveable { mutableIntStateOf(config.channel5) }
    var hidden by rememberSaveable { mutableStateOf(config.hidden) }
    var maxClients by rememberSaveable { mutableIntStateOf(config.maxClients) }
    var wifiMode by rememberSaveable { mutableStateOf(config.wifiMode) }
    var iface by rememberSaveable { mutableStateOf(config.iface) }
    var strict by rememberSaveable { mutableStateOf(config.strictIsolation) }
    var bssid by rememberSaveable { mutableStateOf(config.bssid) }
    var errors by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }

    fun edited(c: HotspotConfig) = c.copy(
        ssid = ssid, channel = channel, band5 = band5, channel5 = channel5, hidden = hidden, maxClients = maxClients,
        wifiMode = wifiMode, iface = iface, strictIsolation = strict, bssid = bssid,
    )
    val dirty = edited(config) != config

    fun discard() {
        ssid = config.ssid; channel = config.channel; band5 = config.band5; channel5 = config.channel5
        hidden = config.hidden; maxClients = config.maxClients
        wifiMode = config.wifiMode; iface = config.iface; strict = config.strictIsolation; bssid = config.bssid
        errors = emptyList()
    }

    fun save() {
        val e = mutableListOf<String>()
        Validation.ssid(ssid)?.let { e += it }
        if (iface == "wlan0" && wifiMode != WifiMode.OFF) e += "Using wlan0 for the hotspot requires turning the phone's Wi-Fi off"
        errors = e
        if (e.isNotEmpty()) return
        HotspotController.updateConfig(edited(config))
        scope.launch { snackbar.showSnackbar(savedMessage("Hotspot settings saved", status.state)) }
    }

    Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (errors.isNotEmpty()) MessageCard("Please fix", errors.joinToString("\n") { "• $it" }, error = true)

            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it }, label = { Text("Wi-Fi name (SSID)") }, singleLine = true,
                supportingText = { Text("${ssid.toByteArray().size}/32 bytes · shared by all networks") }, modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Hidden network", "Don't broadcast the name; devices must type it in.", hidden) { hidden = it }

            // Only the phone's own hotspot (Shizuku) can use 5 GHz: SegAP's access point (root) is 2.4 GHz only (5 GHz was refused when tested).
            // Also while 5 GHz is chosen but not offered (any more): it must stay possible to switch back.
            if (shizuku && (caps.band5GHz || band5)) {
                SectionLabel("Band")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !band5, onClick = { band5 = false }, label = { Text("2.4 GHz") })
                    FilterChip(selected = band5, onClick = { band5 = true }, label = { Text("5 GHz") })
                }
            }
            if (shizuku && band5) {
                SectionLabel("Channel (5 GHz)")
                Text(
                    if (!caps.band5GHz) "This phone's hotspot doesn't offer 5 GHz right now; it will use 2.4 GHz."
                    else "5 GHz is faster but reaches less far, and some older devices can't see it. While the phone itself is " +
                        "on Wi-Fi, it may have to use that Wi-Fi's channel or fall back to 2.4 GHz.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = channel5 == 0, onClick = { channel5 = 0 }, label = { Text("Auto") })
                    caps.channels5.filter { it in HotspotConfig.CHANNELS_5GHZ }.forEach { ch ->
                        FilterChip(selected = ch == channel5, onClick = { channel5 = ch }, label = { Text("$ch") })
                    }
                }
            } else {
                SectionLabel("Channel (2.4 GHz)")
                Text(
                    "Channel $channel · ${channelFrequency(channel)} MHz. 1, 6 and 11 don't overlap each other. " +
                        when {
                            !shizuku -> "SegAP's own access point uses 2.4 GHz (5 GHz was refused when tested); the Shizuku engine can run the phone's own hotspot on 5 GHz where the phone allows it."
                            caps.band5GHz -> ""
                            status.phoneHotspot == null -> ""
                            else -> "This phone's hotspot doesn't offer 5 GHz here."
                        },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HotspotConfig.CHANNELS_24GHZ.forEach { ch ->
                        FilterChip(selected = ch == channel, onClick = { channel = ch }, label = { Text("$ch") })
                    }
                }
            }

            Gated(caps.maxClients, phoneReason(rootHas = true)) {
                SectionLabel("Max devices: $maxClients")
                Slider(
                    value = maxClients.toFloat(), onValueChange = { maxClients = it.roundToInt() }, enabled = caps.maxClients,
                    valueRange = 1f..Validation.MAX_CLIENTS.toFloat(), steps = Validation.MAX_CLIENTS - 2,
                )
            }

            Gated(caps.radioDetails) {
                SectionLabel("Phone's own Wi-Fi while the hotspot runs")
                RadioRow("Keep as is", "If the phone is on Wi-Fi, the hotspot shares that connection (repeater). Otherwise mobile data.",
                    wifiMode == WifiMode.KEEP) { wifiMode = WifiMode.KEEP }
                RadioRow("Turn Wi-Fi off", "Always share mobile data. Wi-Fi is turned back on when the hotspot stops.",
                    wifiMode == WifiMode.OFF) { wifiMode = WifiMode.OFF }
            }

            Gated(caps.firewall) {
                SectionLabel("Isolation")
                SwitchRow(
                    "Strict isolation (recommended)",
                    "On: all traffic between devices passes through the phone's firewall, so networks stay separated. " +
                        "Off: ALL devices on ALL passwords, guests included, can reach each other directly over Wi-Fi " +
                        "(needed for casting / device discovery). Per-network isolation is then not enforced.",
                    strict,
                ) { strict = it }
            }

            Gated(caps.radioDetails) {
                SectionLabel("Advanced")
                Text("Access point MAC (BSSID): $bssid", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { bssid = Mac.randomLocal() }) { Text("New random BSSID") }
                Text("Radio interface", style = MaterialTheme.typography.bodyLarge)
                RadioRow("swlan0 (recommended)", "Works alongside the phone's own Wi-Fi connection.", iface == "swlan0") { iface = "swlan0" }
                RadioRow("wlan0", "Only with the phone's Wi-Fi turned off.", iface == "wlan0") { iface = "wlan0"; wifiMode = WifiMode.OFF }
            }

            Text(
                "Saved settings take effect when you tap Apply changes (or at the next start).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            EngineSection()
            Spacer(Modifier.height(16.dp))
        }
        // Sticky, so Save is reachable without scrolling to the end.
        if (dirty) Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Unsaved changes", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { discard() }) { Text("Discard") }
                Button(onClick = { save() }) { Text("Save") }
            }
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
