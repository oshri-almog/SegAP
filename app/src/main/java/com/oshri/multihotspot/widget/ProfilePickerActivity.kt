package com.oshri.multihotspot.widget

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.ui.MultiHotspotTheme

/**
 * The widget's profile list: a small pop-up over the home screen (widgets can't show a drop-down themselves).
 * Picking a profile makes it the one in use at once; a running hotspot restarts with it.
 */
class ProfilePickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HotspotController.init(this)
        setContent {
            MultiHotspotTheme {
                val config by HotspotController.config.collectAsStateWithLifecycle()
                val status by HotspotController.status.collectAsStateWithLifecycle()
                val running = status.state == EngineState.RUNNING
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Hotspot profile") },
                    text = {
                        Column {
                            config.profiles.forEach { p ->
                                val pick = {
                                    val err = HotspotController.selectProfile(p.id)
                                    when {
                                        err != null -> Toast.makeText(this@ProfilePickerActivity, err, Toast.LENGTH_LONG).show()
                                        p.id != config.activeProfile && running ->
                                            Toast.makeText(this@ProfilePickerActivity, "${p.name}: the hotspot restarts with it", Toast.LENGTH_SHORT).show()
                                    }
                                    finish()
                                }
                                Row(
                                    Modifier.fillMaxWidth().selectable(selected = p.id == config.activeProfile, onClick = pick)
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = p.id == config.activeProfile, onClick = pick)
                                    Column {
                                        Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            "${p.ssid} · channel ${if (p.band5) "${if (p.channel5 == 0) "auto" else p.channel5} (5 GHz)" else p.channel}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (config.profiles.size < 2) Text(
                                "Create more profiles in the app's Hotspot tab.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = { finish() }) { Text("Close") } },
                )
            }
        }
    }
}
