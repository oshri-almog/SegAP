package com.oshri.multihotspot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.oshri.multihotspot.HotspotController
import com.oshri.multihotspot.R
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a notification while the hotspot runs and polls engine status.
 * The engine itself runs as independent root processes, so it survives this service being killed.
 */
class HotspotService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poller: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var foregroundFailed = false

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification("Starting..."),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            foregroundFailed = true
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundFailed) return START_NOT_STICKY
        HotspotController.init(this)
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                HotspotController.stop()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (poller?.isActive != true) poller = scope.launch { pollLoop() }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        var stoppedCount = 0
        while (scope.isActive) {
            HotspotController.refresh()
            val s = HotspotController.status.value
            val c = HotspotController.config.value
            val text = when (s.state) {
                EngineState.RUNNING -> {
                    val n = s.clients.size
                    "${c.ssid} · ch ${c.channel} · $n device${if (n == 1) "" else "s"}" +
                        (if (s.upstream.isBlank()) " · no internet source" else " · via ${s.upstream}") +
                        (if (s.tailscale.isNotBlank()) " · Tailscale" else "")
                }
                EngineState.FAILED -> "Hotspot stopped unexpectedly - open the app"
                else -> "Hotspot is off"
            }
            notify(text)
            // The engine cleans up after itself when the radio is lost: then nothing is alive any more.
            val nothingAlive = !s.daemonAlive && !s.dhcpAlive && !s.hostapdAlive
            if (s.state == EngineState.FAILED && nothingAlive) {
                notifyStopped(s.reason.ifBlank { "Hotspot stopped unexpectedly." })
                stopSelf(); return
            }
            // Stop the service once the engine has been off for a while (e.g. stopped from adb).
            stoppedCount = if (s.state == EngineState.STOPPED) stoppedCount + 1 else 0
            if (stoppedCount >= 3) { stopSelf(); return }
            delay(if (s.state == EngineState.RUNNING) 5_000 else 3_000)
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** One-off (dismissable) notification explaining why the hotspot stopped. */
    private fun notifyStopped(reason: String) {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hotspot)
            .setContentTitle("Hotspot stopped")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(STOPPED_NOTIFICATION_ID, n)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HotspotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hotspot)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "hotspot"
        const val NOTIFICATION_ID = 1
        const val STOPPED_NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.oshri.multihotspot.STOP"

        @Volatile
        var running = false
            private set

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Hotspot status", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
