package com.oshri.multihotspot.engine

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.shizuku.HotspotUserService
import com.oshri.multihotspot.shizuku.IHotspotService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * The Shizuku engine: the phone's own hotspot, configured and switched by [HotspotUserService], which Shizuku runs
 * with the ADB shell user's rights. One network: the first one's password; the rest of SegAP's model needs root.
 */
class ShizukuBackend(private val context: Context) : HotspotBackend {
    override val kind = BackendKind.SHIZUKU

    @Volatile private var service: IHotspotService? = null

    private val args by lazy {
        // A new install gets a new version, so Shizuku replaces a service still running the old code.
        val version = runCatching {
            (context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime / 1000 % Int.MAX_VALUE).toInt()
        }.getOrDefault(1)
        Shizuku.UserServiceArgs(ComponentName(context.packageName, HotspotUserService::class.java.name))
            .tag("segap-hotspot")
            .processNameSuffix("hotspot")
            .daemon(false)
            .version(version)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.takeIf { it.pingBinder() }?.let { IHotspotService.Stub.asInterface(it) }
            synchronized(this@ShizukuBackend) { connecting?.complete(service); connecting = null }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** One bind at a time; everyone who needs the service meanwhile waits for the same one. */
    private var connecting: CompletableDeferred<IHotspotService?>? = null

    /** The service, bound on demand (Shizuku delivers it asynchronously, on the main thread). */
    private suspend fun service(): IHotspotService? {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        service = null
        if (ShizukuSupport.state(context) != ShizukuSupport.State.READY) return null
        var bind = false
        val wait = synchronized(this) {
            connecting ?: CompletableDeferred<IHotspotService?>().also { connecting = it; bind = true }
        }
        if (bind) withContext(Dispatchers.Main) {
            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure { synchronized(this@ShizukuBackend) { connecting?.complete(null); connecting = null } }
        }
        return withTimeoutOrNull(15_000) { wait.await() }
            ?: run { synchronized(this) { if (connecting === wait) connecting = null }; null }
    }

    override suspend fun start(config: HotspotConfig): BackendResult {
        val s = service() ?: return BackendResult(false, "Shizuku isn't available: ${ShizukuSupport.state(context).message}")
        val net = config.groups.first()
        val err = withContext(Dispatchers.IO) {
            runCatching {
                s.start(
                    config.ssid, net.password, config.band5,
                    if (config.band5) config.channel5 else config.channel,
                    config.hidden, config.maxClients, config.blockedMacs, !net.devicesSeeEachOther,
                )
            }.getOrElse { "The Shizuku service stopped responding (${it.message})" }
        }
        return BackendResult(err.isEmpty(), err)
    }

    override suspend fun stop(): BackendResult {
        val s = service() ?: return BackendResult(false, "Shizuku isn't available")
        val err = withContext(Dispatchers.IO) { runCatching { s.stop() }.getOrElse { it.message ?: "error" } }
        return BackendResult(err.isEmpty(), err)
    }

    /** [groupId]: the network the phone's hotspot stands for (the first one). */
    suspend fun status(groupId: String): EngineStatus {
        val s = service() ?: return EngineStatus(state = EngineState.UNKNOWN)
        return withContext(Dispatchers.IO) {
            runCatching { EngineStatus.parse(s.status(groupId)) }.getOrElse { EngineStatus(state = EngineState.UNKNOWN) }
        }
    }

    override suspend fun status(): EngineStatus = status("g1")

    override suspend fun diagnostics(): String = status().log.joinToString("\n")
}
