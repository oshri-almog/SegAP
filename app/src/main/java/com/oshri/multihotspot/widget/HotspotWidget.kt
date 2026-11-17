package com.oshri.multihotspot.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.R
import com.oshri.multihotspot.engine.BackendKind
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.service.HotspotService
import com.oshri.multihotspot.ui.MainActivity
import com.oshri.multihotspot.ui.groupColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home-screen widget: hotspot on/off with a button, Wi-Fi name and channel, and the connected devices with their
 * IPs. It is redrawn whenever the app learns something new (see [observe]): every few seconds while the hotspot
 * runs (the foreground service polls), and on demand (refresh button, the system's periodic update).
 */
class HotspotWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refreshAsync(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshAsync(context)
    }

    /** Draws what's known now, then asks the engine and draws again (within the receiver's time limit). */
    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        HotspotController.init(context)
        render(context, force = true)
        CoroutineScope(Dispatchers.Main).launch {
            try {
                withTimeoutOrNull(8_000) { HotspotController.refresh() }
                render(context, force = true)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.oshri.multihotspot.WIDGET_REFRESH"
        private const val MAX_ROWS = 6

        /** What the widgets show now; nothing is redrawn while it stays the same. */
        @Volatile private var lastShown: String? = null

        /** Redraws the widgets whenever what they show may have changed. */
        fun observe(context: Context, scope: CoroutineScope) {
            val app = context.applicationContext
            scope.launch {
                combine(
                    HotspotController.config, HotspotController.status, HotspotController.busy,
                    HotspotController.error, HotspotController.backend,
                ) { _, _, _, _, _ -> }.collect { render(app) }
            }
        }

        fun render(context: Context, force: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HotspotWidget::class.java))
            if (ids.isEmpty()) return
            val c = HotspotController.config.value
            val s = HotspotController.status.value
            val busy = HotspotController.busy.value
            val error = HotspotController.error.value
            val backend = HotspotController.backend.value
            val running = s.state == EngineState.RUNNING

            val title = when {
                busy != null -> busy
                running -> "Hotspot on"
                s.state == EngineState.STARTING -> "Starting..."
                s.state == EngineState.FAILED -> "Hotspot stopped (error)"
                s.state == EngineState.UNKNOWN && s.reason.isNotBlank() -> "Hotspot state unknown"
                else -> "Hotspot off"
            }
            val profile = c.profiles.firstOrNull { it.id == c.activeProfile }?.name
            val info = buildList {
                add(c.ssid)
                if (running) add(shortRadio(c, s, backend))
                if (running) add(if (s.upstream.isBlank()) "no internet source" else "via ${s.upstream}")
                if (!running && profile != null && c.profiles.size > 1) add("profile $profile")
            }.joinToString(" · ")
            val clients = s.clients.map { cl ->
                val idx = c.groups.indexOfFirst { it.id == cl.groupId }
                Row(cl.hostname ?: "Unknown device", cl.ip ?: "…", c.group(cl.groupId)?.name ?: cl.groupId, groupColor(idx).toArgb())
            }
            val message = error?.lineSequence()?.filter { it.isNotBlank() }?.take(2)?.joinToString(" ")
            val key = listOf(title, info, message, running, clients).toString()
            if (!force && key == lastShown) return
            lastShown = key

            val views = RemoteViews(context.packageName, R.layout.widget_hotspot)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_info, info)
            views.setInt(R.id.widget_icon, "setImageAlpha", if (running) 255 else 120)
            views.setTextViewText(R.id.widget_toggle, if (busy != null) "…" else if (running) "Turn off" else "Turn on")
            views.setInt(R.id.widget_toggle, "setBackgroundResource",
                if (running) R.drawable.widget_button_off else R.drawable.widget_button_on)
            views.setViewVisibility(R.id.widget_message, if (message != null) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_message, message ?: "")

            views.removeAllViews(R.id.widget_clients)
            if (running && clients.isEmpty()) views.addView(R.id.widget_clients, textRow(context, "No devices connected"))
            clients.take(MAX_ROWS).forEach { r ->
                val row = RemoteViews(context.packageName, R.layout.widget_client_row)
                row.setTextColor(R.id.row_dot, r.color)
                row.setTextViewText(R.id.row_name, r.name)
                row.setTextViewText(R.id.row_ip, r.ip)
                row.setTextViewText(R.id.row_network, r.network)
                views.addView(R.id.widget_clients, row)
            }
            if (clients.size > MAX_ROWS) views.addView(R.id.widget_clients, textRow(context, "+${clients.size - MAX_ROWS} more"))

            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(
                context, 0, Intent(context, HotspotWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            // Through the foreground service: Android lets a widget tap start one, and starting or stopping can
            // take longer than a broadcast receiver may run.
            views.setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getForegroundService(
                context, 0, Intent(context, HotspotService::class.java).setAction(HotspotService.ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ))
            manager.updateAppWidget(ids, views)
        }

        /** Band and channel in few words (the widget is narrow): the actual frequency for the phone's own hotspot. */
        private fun shortRadio(c: HotspotConfig, s: EngineStatus, backend: BackendKind?): String {
            val f = s.frequencyMhz
            return when {
                backend == BackendKind.SHIZUKU && f > 0 ->
                    if (f < 3000) "ch ${(f - 2407) / 5} · 2.4 GHz" else "ch ${(f - 5000) / 5} · 5 GHz"
                backend == BackendKind.SHIZUKU && c.band5 -> if (c.channel5 == 0) "5 GHz" else "ch ${c.channel5} · 5 GHz"
                else -> "ch ${c.channel} · 2.4 GHz"
            }
        }

        private data class Row(val name: String, val ip: String, val network: String, val color: Int)

        private fun textRow(context: Context, text: String) = RemoteViews(context.packageName, R.layout.widget_client_row).apply {
            setViewVisibility(R.id.row_dot, View.GONE)
            setTextViewText(R.id.row_name, text)
        }
    }
}
