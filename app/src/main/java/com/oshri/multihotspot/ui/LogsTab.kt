package com.oshri.multihotspot.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.engine.BackendKind
import kotlinx.coroutines.launch

/** Tab 5: engine log and, on request, the radio (hostapd) log. */
@Composable
fun LogsTab(padding: PaddingValues) {
    val status by HotspotController.status.collectAsStateWithLifecycle()
    val backend by HotspotController.backend.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Not saveable: the log can be large (saved state has a size limit); it is re-fetched on demand.
    var radioLog by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (backend == BackendKind.SHIZUKU) "Shizuku engine (the phone's own hotspot): ${status.state.name.lowercase()}"
            else "Engine: ${status.state.name.lowercase()} · radio ${if (status.hostapdAlive) "up" else "down"} · " +
                "DHCP ${if (status.dhcpAlive) "up" else "down"} · monitor ${if (status.daemonAlive) "up" else "down"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        SelectionContainer {
            Text(
                status.log.joinToString("\n").ifBlank { "(empty)" },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        // The Shizuku engine has no radio log of its own: its log is the one above.
        if (backend != BackendKind.SHIZUKU) OutlinedButton(onClick = { scope.launch { radioLog = HotspotController.radioLog() } }) {
            Text(if (radioLog == null) "Show radio (hostapd) log" else "Refresh radio (hostapd) log")
        }
        if (backend != BackendKind.SHIZUKU) radioLog?.let {
            SelectionContainer {
                Text(it.ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}
