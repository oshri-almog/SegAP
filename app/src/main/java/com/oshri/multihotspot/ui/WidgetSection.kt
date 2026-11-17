package com.oshri.multihotspot.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.oshri.multihotspot.widget.HotspotWidget

/** Hotspot tab, bottom: put the home-screen widget on the home screen (the launcher asks to confirm). */
@Composable
fun WidgetSection() {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Home-screen widget")
        Text(
            "Shows whether the hotspot is on, its Wi-Fi name and channel, and the connected devices with their IPs, " +
                "with a button to turn the hotspot on or off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (manager.isRequestPinAppWidgetSupported) {
            OutlinedButton(onClick = {
                manager.requestPinAppWidget(ComponentName(context, HotspotWidget::class.java), null, null)
            }) { Text("Add to home screen") }
        } else {
            Text(
                "Add it from the home screen: long-press an empty spot, choose Widgets, then SegAP.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
