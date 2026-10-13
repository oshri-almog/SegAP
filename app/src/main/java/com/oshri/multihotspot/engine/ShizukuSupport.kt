package com.oshri.multihotspot.engine

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Whether the Shizuku engine can be used, and the steps to get there. */
object ShizukuSupport {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
    private const val PERMISSION_REQUEST = 7310

    enum class State(val message: String) {
        NOT_INSTALLED("Shizuku isn't installed."),
        NOT_RUNNING("Shizuku is installed but not running. Open it and start it (wireless debugging), or via adb."),
        NO_PERMISSION("Shizuku is running; SegAP needs its permission."),
        READY("Shizuku is running and SegAP may use it."),
    }

    fun state(context: Context): State {
        val running = runCatching { Shizuku.pingBinder() && !Shizuku.isPreV11() }.getOrDefault(false)
        if (!running) return if (installed(context)) State.NOT_RUNNING else State.NOT_INSTALLED
        val granted = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        return if (granted) State.READY else State.NO_PERMISSION
    }

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Shows Shizuku's permission dialog; [onResult] gets whether it was granted. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != PERMISSION_REQUEST) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(PERMISSION_REQUEST)
        }.onFailure {
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    /** Opens the Shizuku app (to start it), if installed. */
    fun launch(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
