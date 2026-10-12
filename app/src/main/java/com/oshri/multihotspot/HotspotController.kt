package com.oshri.multihotspot

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.engine.Engine
import com.oshri.multihotspot.engine.RootShell
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.service.HotspotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** App-wide state: saved config, engine status, start/stop. */
object HotspotController {

    private lateinit var appContext: Context
    private lateinit var store: ConfigStore
    private lateinit var appliedStore: ConfigStore
    private lateinit var attemptedStore: ConfigStore
    private lateinit var engine: Engine
    private val opLock = Mutex()

    /** Start/stop must finish even if the screen that triggered them goes away (back, rotation, navigation). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _config = MutableStateFlow(HotspotConfig.default())
    val config: StateFlow<HotspotConfig> = _config.asStateFlow()

    /** Config the running engine was started with (null when stopped or started outside the app). */
    private val _applied = MutableStateFlow<HotspotConfig?>(null)
    val applied: StateFlow<HotspotConfig?> = _applied.asStateFlow()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** Non-null while starting/stopping: what is happening. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _root = MutableStateFlow<Boolean?>(null)
    val rootAvailable: StateFlow<Boolean?> = _root.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = ConfigStore(File(appContext.filesDir, "config.json"))
        appliedStore = ConfigStore(File(appContext.filesDir, "applied.json"))
        attemptedStore = ConfigStore(File(appContext.filesDir, "attempted.json"))
        engine = Engine(appContext)
        _config.value = store.load()
    }

    fun updateConfig(newConfig: HotspotConfig) {
        _config.value = newConfig
        store.save(newConfig)
    }

    fun clearError() { _error.value = null }

    /** Fire-and-forget versions for UI callbacks. */
    fun startAsync() {
        if (_busy.value == null) appScope.launch { start() }
    }

    fun stopAsync() {
        appScope.launch { stop() }
    }

    suspend fun checkRoot(): Boolean = RootShell.isAvailable().also { _root.value = it }

    private val rates = com.oshri.multihotspot.model.RateTracker({ android.os.SystemClock.elapsedRealtime() })

    suspend fun refresh() {
        if (_root.value != true && !checkRoot()) return
        val raw = engine.status()
        val s = raw.copy(clients = rates.apply(raw.clients))
        _status.value = s
        if (s.state == EngineState.RUNNING) {
            if (_applied.value == null) _applied.value = appliedFile().takeIf { it.exists() }?.let { appliedStore.load() }
            // The engine may run the last attempted config even though the app didn't see the start finish
            // (e.g. it timed out): trust the engine's fingerprint of the files it actually uses.
            val attempted = attemptedFile().takeIf { it.exists() }?.let { attemptedStore.load() }
            if (attempted != null && attempted != _applied.value && s.configSum.isNotEmpty() && s.configSum == engine.writtenConfigSum()) {
                _applied.value = attempted
                appliedStore.save(attempted)
            }
            ensureService()
        } else if (s.state == EngineState.STOPPED || s.state == EngineState.FAILED) {
            _applied.value = null
        }
    }

    /** Validates, then (re)starts the engine with the current config. */
    suspend fun start(): Boolean {
        // Reject a second tap before suspending anywhere (busy is only touched on the main thread).
        if (_busy.value != null) return false
        _busy.value = if (_status.value.state == EngineState.RUNNING) "Applying changes..." else "Starting..."
        try {
            return opLock.withLock { doStart() }
        } finally {
            _busy.value = null
        }
    }

    private suspend fun doStart(): Boolean {
        val c = _config.value
        val problems = Validation.config(c)
        if (problems.isNotEmpty()) {
            _error.value = problems.joinToString("\n")
            return false
        }
        if (!checkRoot()) {
            _error.value = "Root access is not available. Reboot through recovery (adb reboot recovery) so Magisk loads, then grant root to SegAP."
            return false
        }
        _error.value = null
        // Start the foreground service now, while the user is still in the app: starting it from the
        // background after a slow engine start is not allowed on Android 12+.
        ensureService()
        attemptedStore.save(c)
        val r = engine.start(c)
        if (!r.ok || !r.output.contains("OK")) {
            val detail = r.output.lines().filter { it.startsWith("ERROR") }.joinToString("\n").ifBlank { r.output.takeLast(400) }
            _error.value = "Hotspot failed to start:\n$detail\n\n${engine.hostapdLog().takeLast(800)}"
            refresh()
            if (_status.value.state != EngineState.RUNNING) stopService()
            return false
        }
        _applied.value = c
        appliedStore.save(c)
        refresh()
        return true
    }

    suspend fun stop() {
        _busy.value = "Stopping..."
        try {
            opLock.withLock {
                val r = engine.stop()
                if (!r.ok) _error.value = "Stop reported a problem:\n${r.output.takeLast(400)}"
                _applied.value = null
                appliedFile().delete()
                refresh()
            }
        } finally {
            _busy.value = null
        }
        stopService()
    }

    suspend fun radioLog(): String = engine.hostapdLog()

    private fun appliedFile() = File(appContext.filesDir, "applied.json")
    private fun attemptedFile() = File(appContext.filesDir, "attempted.json")

    private fun ensureService() {
        if (HotspotService.running) return
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, HotspotService::class.java))
        } catch (e: IllegalStateException) {
            // App is in the background: not allowed now. The next refresh from the UI starts it.
        }
    }

    private fun stopService() {
        appContext.stopService(Intent(appContext, HotspotService::class.java))
    }
}
