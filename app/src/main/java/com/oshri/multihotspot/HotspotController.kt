package com.oshri.multihotspot

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.data.Prefs
import com.oshri.multihotspot.engine.BackendKind
import com.oshri.multihotspot.engine.Capabilities
import com.oshri.multihotspot.engine.Engine
import com.oshri.multihotspot.engine.EngineMode
import com.oshri.multihotspot.engine.HotspotBackend
import com.oshri.multihotspot.engine.RootBackend
import com.oshri.multihotspot.engine.RootShell
import com.oshri.multihotspot.engine.ShizukuBackend
import com.oshri.multihotspot.engine.ShizukuSupport
import com.oshri.multihotspot.engine.TailscalePackage
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.ShizukuConfigPrint
import com.oshri.multihotspot.model.TailscaleInstance
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.normalized
import com.oshri.multihotspot.model.shizukuView
import com.oshri.multihotspot.service.HotspotService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.File

/** App-wide state: saved config, engine status, start/stop. */
object HotspotController {

    private lateinit var appContext: Context
    private lateinit var store: ConfigStore
    private lateinit var appliedStore: ConfigStore
    private lateinit var attemptedStore: ConfigStore
    private lateinit var prefs: Prefs
    private lateinit var engine: Engine
    private lateinit var rootBackend: RootBackend
    private lateinit var shizukuBackend: ShizukuBackend
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

    private val _engineMode = MutableStateFlow(EngineMode.AUTO)
    /** Which engine the user wants (Automatic = root when available, Shizuku otherwise). */
    val engineMode: StateFlow<EngineMode> = _engineMode.asStateFlow()

    private val _backend = MutableStateFlow<BackendKind?>(null)
    /** The engine in use; null = neither root nor Shizuku is available (yet). */
    val backend: StateFlow<BackendKind?> = _backend.asStateFlow()

    private val _shizuku = MutableStateFlow(ShizukuSupport.State.NOT_INSTALLED)
    val shizukuState: StateFlow<ShizukuSupport.State> = _shizuku.asStateFlow()

    private val _owner = MutableStateFlow<BackendKind?>(null)
    /** The engine that started the hotspot and hasn't reported it off yet (see [Prefs.owner]). */
    val owner: StateFlow<BackendKind?> = _owner.asStateFlow()

    /** Whether the engine choice is locked: something is starting/stopping, or an engine within reach owns the hotspot. */
    fun engineLocked(owner: BackendKind?, shizuku: ShizukuSupport.State, busy: String?): Boolean =
        busy != null || (owner != null && !(owner == BackendKind.SHIZUKU && shizuku != ShizukuSupport.State.READY))

    private val _engineChecked = MutableStateFlow(false)
    /** False until the first look for root/Shizuku has finished (so the UI doesn't flash "no engine"). */
    val engineChecked: StateFlow<Boolean> = _engineChecked.asStateFlow()

    /** What the engine in use can do; the UI greys out the rest. */
    val capabilities: StateFlow<Capabilities> = combine(_backend, _status) { kind, s ->
        when (kind) {
            BackendKind.ROOT -> Capabilities.ROOT
            BackendKind.SHIZUKU -> Capabilities.shizuku(s.phoneHotspot)
            null -> Capabilities.NONE
        }
    }.stateIn(appScope, SharingStarted.Eagerly, Capabilities.NONE)

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        store = ConfigStore(File(appContext.filesDir, "config.json"))
        appliedStore = ConfigStore(File(appContext.filesDir, "applied.json"))
        attemptedStore = ConfigStore(File(appContext.filesDir, "attempted.json"))
        prefs = Prefs(appContext)
        engine = Engine(appContext)
        rootBackend = RootBackend(engine)
        shizukuBackend = ShizukuBackend(appContext)
        _config.value = store.load()
        _engineMode.value = prefs.engineMode
        _owner.value = prefs.owner
        // Shizuku hands over its binder asynchronously (and again after it restarts).
        Shizuku.addBinderReceivedListenerSticky { _shizuku.value = ShizukuSupport.state(appContext); appScope.launch { refresh() } }
        Shizuku.addBinderDeadListener { _shizuku.value = ShizukuSupport.state(appContext) }
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

    /**
     * Only while no engine owns a hotspot: switching under a running (or half-stopped) one would lose track of it.
     * Exception: the owner can't be reached (Shizuku stopped) - then the user may move on.
     */
    fun setEngineMode(mode: EngineMode) {
        if (_busy.value != null || (_owner.value != null && !ownerUnreachable())) return
        if (_owner.value != null) forgetOwner()
        prefs.engineMode = mode
        _engineMode.value = mode
        _root.value = _root.value.takeIf { it == true } // a "no root" from before may be stale now
        appScope.launch { refresh() }
    }

    /** The engine owning the hotspot is Shizuku, and Shizuku is gone (stopped, or its permission revoked). */
    private fun ownerUnreachable(): Boolean =
        _owner.value == BackendKind.SHIZUKU && _shizuku.value != ShizukuSupport.State.READY

    fun requestShizukuPermission() {
        ShizukuSupport.requestPermission { appScope.launch { refresh() } }
    }

    fun openShizuku(): Boolean = ShizukuSupport.launch(appContext)

    /** The app came to the foreground: root may have been granted or Shizuku started meanwhile. */
    suspend fun onVisible() {
        _shizuku.value = ShizukuSupport.state(appContext)
        // Automatic mode running on Shizuku without root: don't run su (a Magisk prompt or toast) every time.
        val onShizuku = _engineMode.value == EngineMode.AUTO && _root.value == false && _shizuku.value == ShizukuSupport.State.READY
        if (_engineMode.value != EngineMode.SHIZUKU && !onShizuku) checkRoot()
    }

    suspend fun checkRoot(): Boolean = RootShell.isAvailable().also { _root.value = it; lastRootCheck = SystemClock.elapsedRealtime() }

    private var lastRootCheck = 0L

    /** Root, checked again while it's missing at most every 30 s (10 min while Shizuku can stand in); each check runs su. */
    private suspend fun rootOk(): Boolean {
        if (_root.value == true) return true
        val wait = if (_shizuku.value == ShizukuSupport.State.READY) 600_000 else 30_000
        if (_root.value == false && SystemClock.elapsedRealtime() - lastRootCheck < wait) return false
        return checkRoot()
    }

    /** The engine to use now: the one starting/stopping or owning the hotspot, else per the user's choice. */
    private suspend fun chooseBackend(): BackendKind? {
        val shz = ShizukuSupport.state(appContext).also { _shizuku.value = it }
        if (_busy.value != null || opLock.isLocked) _backend.value?.let { return it }
        _owner.value?.let { owner ->
            // Without root nothing the root engine started can still run (e.g. rebooted without Magisk).
            if (owner == BackendKind.ROOT && !rootOk()) forgetOwner() else return owner
        }
        val ready = shz == ShizukuSupport.State.READY
        return when (_engineMode.value) {
            EngineMode.ROOT -> BackendKind.ROOT.takeIf { rootOk() }
            EngineMode.SHIZUKU -> BackendKind.SHIZUKU.takeIf { ready }
            EngineMode.AUTO -> if (rootOk()) BackendKind.ROOT else BackendKind.SHIZUKU.takeIf { ready }
        }
    }

    private fun setOwner(kind: BackendKind) {
        prefs.owner = kind
        _owner.value = kind
    }

    /** The hotspot is off (or out of reach for good): no engine owns it, and nothing is applied. */
    private fun forgetOwner() {
        prefs.owner = null
        _owner.value = null
        _applied.value = null
        appliedFile().delete()
    }

    private fun backendFor(kind: BackendKind): HotspotBackend = when (kind) {
        BackendKind.ROOT -> rootBackend
        BackendKind.SHIZUKU -> shizukuBackend
    }

    /** What has to be fixed before starting with [kind]. Shizuku runs the first network only: the rest needn't be valid. */
    fun problems(c: HotspotConfig, kind: BackendKind?): List<String> =
        if (kind == BackendKind.SHIZUKU) Validation.shizukuConfig(c) else Validation.config(c)

    /** Whether the running hotspot uses older settings than the saved ones (only what its engine uses counts). */
    fun needsApply(applied: HotspotConfig?, config: HotspotConfig, status: EngineStatus, kind: BackendKind?): Boolean =
        status.state == EngineState.RUNNING && when (kind) {
            BackendKind.SHIZUKU -> applied?.shizukuView() != config.shizukuView()
            else -> applied?.normalized() != config.normalized()
        }

    private val rates = com.oshri.multihotspot.model.RateTracker({ android.os.SystemClock.elapsedRealtime() })
    private var tailscaleGcDone = false

    suspend fun refresh() {
        val kind = chooseBackend()
        _backend.value = kind
        _engineChecked.value = true
        when (kind) {
            null -> _status.value = EngineStatus(state = EngineState.UNKNOWN)
            BackendKind.ROOT -> refreshRoot()
            BackendKind.SHIZUKU -> refreshShizuku()
        }
    }

    private suspend fun refreshShizuku() {
        val raw = shizukuBackend.status(_config.value.groups.first().id)
        if (raw.state == EngineState.UNKNOWN && _owner.value == BackendKind.SHIZUKU) {
            // The hotspot may well still be on: say so instead of "off".
            _status.value = raw.copy(reason = "SegAP can't reach Shizuku. If the hotspot is still on, start Shizuku again, " +
                "or turn the hotspot off in Quick Settings.")
            return
        }
        val s = raw.copy(clients = rates.apply(raw.clients))
        _status.value = s
        // The phone's hotspot settings can be changed in Android's settings: then they're no longer SegAP's.
        val applied = _applied.value ?: appliedFile().takeIf { it.exists() }?.let { appliedStore.load() }
        if (s.state == EngineState.RUNNING && applied != null && s.liveConfigPrint.isNotEmpty() &&
            s.liveConfigPrint != ShizukuConfigPrint.of(applied) && !opLock.isLocked) {
            _applied.value = null
            appliedFile().delete()
        }
        trackApplied(s, BackendKind.SHIZUKU)
    }

    private suspend fun refreshRoot() {
        val raw = engine.status()
        val s = raw.copy(clients = rates.apply(raw.clients))
        _status.value = s
        // Once per app process: an editor that was open when the app died can't stop the instance it started.
        if (!tailscaleGcDone && s.state != EngineState.UNKNOWN) {
            tailscaleGcDone = true
            val known = _config.value.groups.map { it.id } + (appliedFile().takeIf { it.exists() }?.let { appliedStore.load() }?.groups?.map { it.id } ?: emptyList())
            if (s.tailscaleVersion.isNotBlank()) engine.tailscaleGc(known.distinct())
        }
        if (s.state == EngineState.RUNNING) {
            // The engine may run the last attempted config even though the app didn't see the start finish
            // (e.g. it timed out): trust the engine's fingerprint of the files it actually uses.
            if (_applied.value == null) _applied.value = appliedFile().takeIf { it.exists() }?.let { appliedStore.load() }
            val attempted = attemptedFile().takeIf { it.exists() }?.let { attemptedStore.load() }
            if (attempted != null && attempted != _applied.value && s.configSum.isNotEmpty() && s.configSum == engine.writtenConfigSum()) {
                _applied.value = attempted
                appliedStore.save(attempted)
            }
        }
        trackApplied(s, BackendKind.ROOT)
    }

    private fun trackApplied(s: EngineStatus, kind: BackendKind) {
        if (s.state == EngineState.RUNNING || s.state == EngineState.STARTING) {
            // Running but started outside SegAP (e.g. Quick Settings): this engine owns it now.
            if (_owner.value == null && _busy.value == null) setOwner(kind)
            if (_applied.value == null && s.state == EngineState.RUNNING) {
                _applied.value = appliedFile().takeIf { it.exists() }?.let { appliedStore.load() }
            }
            if (s.state == EngineState.RUNNING) ensureService()
            return
        }
        // Not while starting or stopping: a restart passes through "stopped".
        if (_busy.value != null || opLock.isLocked) return
        val off = s.state == EngineState.STOPPED ||
            (s.state == EngineState.FAILED && !(s.hostapdAlive || s.dhcpAlive || s.daemonAlive)) // root: leftovers to clean up
        if (off && _owner.value == kind) forgetOwner()
        else if (off) _applied.value = null
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

    /** Why there's no engine, and what to do. */
    fun noEngineMessage(): String = when (_engineMode.value) {
        EngineMode.ROOT -> "Root access is not available. Allow root for SegAP in Magisk (some phones only load Magisk " +
            "when booted through recovery: adb reboot recovery)."
        EngineMode.SHIZUKU -> "Shizuku isn't ready: ${_shizuku.value.message}"
        EngineMode.AUTO -> "SegAP needs root (Magisk) or Shizuku. ${_shizuku.value.message}"
    }

    private suspend fun doStart(): Boolean {
        val c = _config.value
        val kind = chooseBackend()
        _backend.value = kind
        if (kind == null) {
            _error.value = noEngineMessage()
            return false
        }
        val problems = problems(c, kind)
        if (problems.isNotEmpty()) {
            _error.value = problems.joinToString("\n")
            return false
        }
        _error.value = null
        // Start the foreground service now, while the user is still in the app: starting it from the
        // background after a slow engine start is not allowed on Android 12+.
        ensureService()
        attemptedStore.save(c)
        setOwner(kind)
        val r = backendFor(kind).start(c)
        if (!r.ok) {
            _error.value = "Hotspot failed to start:\n${r.message}"
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
        var off = false
        try {
            opLock.withLock {
                val kind = _owner.value ?: _backend.value ?: chooseBackend()
                if (kind == null) {
                    _error.value = "SegAP can't reach the engine running the hotspot. Turn the hotspot off in Quick Settings."
                    return@withLock
                }
                val r = backendFor(kind).stop()
                if (!r.ok) _error.value = "Stop reported a problem:\n${r.message.takeLast(400)}"
                refresh()
                val s = _status.value
                // Only forget it when the engine confirms it's off; otherwise the hotspot may still be on.
                off = s.state == EngineState.STOPPED ||
                    (s.state == EngineState.FAILED && !(s.hostapdAlive || s.dhcpAlive || s.daemonAlive))
                if (off) forgetOwner()
                else if (r.ok && s.state == EngineState.UNKNOWN) _error.value = "Couldn't confirm that the hotspot is off."
            }
        } finally {
            _busy.value = null
        }
        if (off) stopService() else refresh()
    }


    suspend fun radioLog(): String = _backend.value?.let { backendFor(it).diagnostics() } ?: ""

    // ---- Tailscale for exit nodes

    /** Download/install of SegAP's Tailscale package; null = idle. */
    data class TailscaleSetup(val message: String, val progress: Float? = null, val failed: Boolean = false)

    private val _tailscaleSetup = MutableStateFlow<TailscaleSetup?>(null)
    val tailscaleSetup: StateFlow<TailscaleSetup?> = _tailscaleSetup.asStateFlow()

    fun installTailscaleAsync() {
        if (_tailscaleSetup.value?.failed == false) return // already in progress
        appScope.launch {
            val v = TailscalePackage.VERSION
            val tgz = File(appContext.cacheDir, "tailscale_${v}_arm64.tgz")
            _tailscaleSetup.value = TailscaleSetup("Downloading Tailscale $v...", 0f)
            val dl = TailscalePackage.download(tgz) { done, total ->
                _tailscaleSetup.value = TailscaleSetup("Downloading Tailscale $v (${done / 1_048_576} of ${total / 1_048_576} MB)...", done.toFloat() / total)
            }
            if (dl.isFailure) {
                _tailscaleSetup.value = TailscaleSetup("Download failed: ${dl.exceptionOrNull()?.message ?: "unknown error"}", failed = true)
                return@launch
            }
            _tailscaleSetup.value = TailscaleSetup("Installing Tailscale $v...")
            val r = engine.tailscaleInstall(tgz, TailscalePackage.SHA256)
            tgz.delete()
            _tailscaleSetup.value = if (r.isSuccess) null
            else TailscaleSetup("Install failed: ${r.exceptionOrNull()?.message ?: "unknown error"}", failed = true)
            refresh()
        }
    }

    fun clearTailscaleSetupError() {
        if (_tailscaleSetup.value?.failed == true) _tailscaleSetup.value = null
    }

    /** Starts the group's instance (and a login if needed); returns its state. */
    suspend fun tailscaleUp(groupId: String, hostname: String): TailscaleInstance = engine.tailscaleUp(groupId, hostname)

    suspend fun tailscaleStatus(groupId: String): TailscaleInstance = engine.tailscaleStatus(groupId)

    /** Stops the group's instance unless the running hotspot uses it (it keeps its login). */
    fun tailscaleReleaseAsync(groupId: String) {
        val inUse = _status.value.state == EngineState.RUNNING && _applied.value?.group(groupId)?.hasTailscaleInstance == true
        if (!inUse && _root.value == true) appScope.launch { engine.tailscaleDown(groupId) }
    }

    /** Signs the group's instance out of the tailnet and forgets it (network deleted or signed out). */
    fun tailscaleLogoutAsync(groupId: String) {
        if (_root.value != true) return // instances only exist with root (ts-gc cleans up later otherwise)
        appScope.launch { engine.tailscaleLogout(groupId) }
    }

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
