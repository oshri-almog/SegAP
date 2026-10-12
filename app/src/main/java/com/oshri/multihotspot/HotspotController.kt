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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** App-wide state: saved config, engine status, start/stop. */
object HotspotController {

    private lateinit var appContext: Context
    private lateinit var store: ConfigStore
    private lateinit var appliedStore: ConfigStore
    private lateinit var engine: Engine
    private val opLock = Mutex()

    private val _config = MutableStateFlow(HotspotConfig.default())
    val config: StateFlow<HotspotConfig> = _config.asStateFlow()

    /** Config the running engine was started with (null when stopped). */
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
        engine = Engine(appContext)
        _config.value = store.load()
    }

    fun updateConfig(newConfig: HotspotConfig) {
        _config.value = newConfig
        store.save(newConfig)
    }

    fun clearError() { _error.value = null }

    suspend fun checkRoot(): Boolean = RootShell.isAvailable().also { _root.value = it }

    suspend fun refresh() {
        if (_root.value != true && !checkRoot()) return
        val s = engine.status()
        _status.value = s
        if (s.state == EngineState.RUNNING) {
            if (_applied.value == null) _applied.value = appliedFile().takeIf { it.exists() }?.let { appliedStore.load() }
            ensureService()
        } else if (s.state == EngineState.STOPPED || s.state == EngineState.FAILED) {
            _applied.value = null
        }
    }

    /** Validates, then (re)starts the engine with the current config. */
    suspend fun start(): Boolean = opLock.withLock {
        val c = _config.value
        val problems = Validation.config(c)
        if (problems.isNotEmpty()) {
            _error.value = problems.joinToString("\n")
            return false
        }
        if (!checkRoot()) {
            _error.value = "Root access is not available. Reboot through recovery (adb reboot recovery) so Magisk loads, then grant root to MultiHotspot."
            return false
        }
        _error.value = null
        _busy.value = if (_status.value.state == EngineState.RUNNING) "Applying changes..." else "Starting..."
        try {
            val r = engine.start(c)
            if (!r.ok || !r.output.contains("OK")) {
                val detail = r.output.lines().filter { it.startsWith("ERROR") }.joinToString("\n").ifBlank { r.output.takeLast(400) }
                _error.value = "Hotspot failed to start:\n$detail\n\n${engine.hostapdLog().takeLast(800)}"
                refresh()
                return false
            }
            _applied.value = c
            appliedStore.save(c)
            refresh()
            ensureService()
            return true
        } finally {
            _busy.value = null
        }
    }

    suspend fun stop() = opLock.withLock {
        _busy.value = "Stopping..."
        try {
            val r = engine.stop()
            if (!r.ok) _error.value = "Stop reported a problem:\n${r.output.takeLast(400)}"
            _applied.value = null
            appliedFile().delete()
            refresh()
        } finally {
            _busy.value = null
        }
        appContext.stopService(Intent(appContext, HotspotService::class.java))
    }

    suspend fun radioLog(): String = engine.hostapdLog()

    private fun appliedFile() = File(appContext.filesDir, "applied.json")

    private fun ensureService() {
        if (HotspotService.running) return
        ContextCompat.startForegroundService(appContext, Intent(appContext, HotspotService::class.java))
    }
}
