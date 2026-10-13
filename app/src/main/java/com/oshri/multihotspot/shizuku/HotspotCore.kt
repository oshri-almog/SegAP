package com.oshri.multihotspot.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.MacAddress
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.oshri.multihotspot.model.ShizukuConfigPrint
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Controls the phone's own Wi-Fi hotspot with the ADB shell user's rights (NETWORK_SETTINGS, TETHER_PRIVILEGED).
 * Runs inside the Shizuku user service process (app_process as uid 2000), never in the app.
 *
 * The APIs used are @SystemApi members of public classes (SoftApConfiguration.Builder, WifiManager, TetheringManager),
 * so they're called by reflection; their callbacks are interfaces, implemented with [Proxy].
 * Calls that carry a calling package must name one that belongs to the shell uid: every manager is created from a
 * context of "com.android.shell".
 * Lint's non-SDK warnings are about the app's own process; this code only runs as the shell user, where the
 * hidden-API restrictions don't apply.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi")
class HotspotCore {

    private val log = ArrayDeque<String>()

    private val thread = HandlerThread("segap-hotspot").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val context: Context = shellContext()
    // Built straight from their binder services: the system-service registry needs app-process state this
    // process doesn't have (e.g. Android 16's SystemFeaturesCache).
    private val wifi: Any = wifiManager(context, thread.looper)
    private val tethering: Any = tetheringManager(context)
    private val connectivity: ConnectivityManager? = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
    /**
     * Samsung's own Wi-Fi service (ISemWifiManager, "sem_wifi"), if present. One UI saves the hotspot settings and
     * switches it on/off through it (not Android's tethering API, which on some Samsungs drops the phone's own
     * Wi-Fi connection instead of sharing it); it also carries max devices and client isolation.
     */
    private val sem: Any? = runCatching {
        Class.forName("com.samsung.android.wifi.ISemWifiManager\$Stub")
            .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder("sem_wifi"))
    }.getOrNull()

    // Last known state from the callbacks (written on [thread], read from binder threads).
    @Volatile private var tetheredIfaces: List<String> = emptyList()
    @Volatile private var upstream: String = ""
    @Volatile private var frequency: Int = 0
    @Volatile private var associated: List<String> = emptyList()
    @Volatile private var dhcp: Map<String, Pair<String, String>> = emptyMap() // mac -> (ip, hostname)
    private val firstSeen = HashMap<String, Long>()
    /** SoftApCapability from the callback (null until the first report). Declared before init: see register(). */
    @Volatile private var capability: Any? = null

    /** SoftApConfiguration.Builder has setClientIsolationEnabled (Android 16 here; absent on the S10's Android 12). */
    private val hasIsolation = runCatching {
        Class.forName("android.net.wifi.SoftApConfiguration\$Builder").methods.any { it.name == "setClientIsolationEnabled" }
    }.getOrDefault(false)

    init {
        register()
        log("ready (Android ${android.os.Build.VERSION.SDK_INT}, uid ${android.os.Process.myUid()})")
    }

    // ------------------------------------------------------------------ commands

    /**
     * Applies the configuration and (re)starts Wi-Fi tethering. Returns "" or an error.
     * [channel] 0 = automatic. [isolate]: devices can't reach each other (only where the platform supports it).
     */
    @Synchronized
    fun start(ssid: String, passphrase: String, band5: Boolean, channel: Int, hidden: Boolean, maxClients: Int,
              blocked: List<String>, isolate: Boolean): String = runCatching {
        // Client control (below) is only known after the first capability report, which comes in just after startup.
        if (capability == null) waitFor(2_000) { capability != null }
        val current = currentConfig()
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val b = builderClass.getConstructor(current.javaClass).newInstance(current)
        fun set(name: String, vararg args: Any) = invokeBest(b, name, *args)
        set("setSsid", ssid)
        // Keep WPA3 (transition) if the phone uses it; open networks become WPA2.
        val sec = current.javaClass.getMethod("getSecurityType").invoke(current) as Int
        set("setPassphrase", passphrase, if (sec in 1..3) sec else SECURITY_WPA2)
        val band = if (band5) BAND_5GHZ else BAND_2GHZ
        if (channel > 0) set("setChannel", channel, band) else set("setBand", band)
        set("setHiddenSsid", hidden)
        // Max clients and the block list need the driver's "client force disconnect" support; asking for them
        // without it makes Android reject the whole configuration (the S10: "Client control requires HAL support").
        val clientControl = supports(FEATURE_CLIENT_FORCE_DISCONNECT)
        set("setMaxNumberOfClients", if (clientControl) maxClients else 0)
        set("setBlockedClientList", if (clientControl) blocked.mapNotNull { runCatching { MacAddress.fromString(it) }.getOrNull() } else emptyList())
        set("setClientControlByUserEnabled", false)
        if (hasMethod(b, "setClientIsolationEnabled")) set("setClientIsolationEnabled", isolate)
        val config = call(b, "build")!!
        val samsung = sem
        // Samsung's setter may report a refusal as false (like Android's) instead of throwing.
        val saved = samsung != null && runCatching { call(samsung, "setSoftApConfiguration", config) != false }
            .onFailure { log("Samsung settings call failed: ${it.rootMessage()}") }.getOrDefault(false)
        if (!saved && call(wifi, "setSoftApConfiguration", config) != true) {
            return@runCatching "The phone rejected the hotspot settings (channel or band not allowed here?)"
        }
        if (samsung != null) {
            // Samsung keeps max devices and isolation outside SoftApConfiguration.
            // A failure is logged and stops the feature being offered (status), rather than failing the start.
            if (!clientControl) runCatching { call(samsung, "setWifiApMaxClientToFramework", maxClients) }
                .recoverCatching { call(samsung, "setWifiApMaxClient", maxClients) }
                .onFailure { semMaxClientsFailed = true; log("max devices not applied: ${it.rootMessage()}") }
            runCatching { call(samsung, "setWifiApIsolate", isolate) }
                .onFailure { semIsolationFailed = true; log("isolation not applied: ${it.rootMessage()}") }
        }
        log("settings${if (saved) " (Samsung)" else ""}: \"$ssid\" ${if (band5) "5" else "2.4"} GHz channel " +
            "${if (channel > 0) channel else "auto"}, max $maxClients" + (if (blocked.isNotEmpty()) ", ${blocked.size} blocked" else ""))

        if (apState() == AP_ENABLED || apState() == AP_ENABLING) {
            switchOff()
            waitFor(10_000) { apState() == AP_DISABLED || apState() == AP_FAILED }
        }
        switchOn()
    }.getOrElse { "Shizuku engine: ${it.rootMessage()}" }.also { if (it.isNotEmpty()) log("start failed: $it") }

    /**
     * Test only: switch the hotspot on with the phone's current settings (nothing is changed), report, and switch it
     * off again after [seconds].
     */
    @Synchronized
    fun trial(seconds: Int): String {
        val err = runCatching { switchOn() }.getOrElse { it.rootMessage() }
        Thread.sleep(seconds * 1000L)
        val report = "trial start: '$err'\n" + status("g1")
        runCatching { switchOff() }
        waitFor(10_000) { apState() == AP_DISABLED || apState() == AP_FAILED }
        return report + "after stop: state ${apState()}\n"
    }

    /** Samsung's max-devices / isolation setters failed once: don't offer them (they'd silently not apply). */
    @Volatile private var semMaxClientsFailed = false
    @Volatile private var semIsolationFailed = false

    /**
     * The hotspot settings tethering uses: Android's. (Samsung's service keeps a copy that its setter updates too, but
     * where that setter is refused - the S24 - the copy goes stale.)
     */
    private fun currentConfig(): Any =
        runCatching { call(wifi, "getSoftApConfiguration") }.getOrNull() ?: call(sem!!, "getSoftApConfiguration")!!

    /** Samsung's switch refused the shell once (it needs NETWORK_STACK on some builds): don't ask again. */
    @Volatile private var semSwitchDenied = false

    /** Samsung's switch first (shares the phone's Wi-Fi like One UI does); Android's tethering API otherwise. */
    private fun switchOn(): String {
        val samsung = sem
        if (samsung != null && !semSwitchDenied) {
            val ok = runCatching { call(samsung, "setWifiApEnabled", null, true) as? Boolean ?: true }
                .onFailure {
                    if (it.rootCause() is SecurityException) semSwitchDenied = true
                    log("Samsung switch not available: ${it.rootMessage()}")
                }.getOrDefault(false)
            if (ok) {
                waitFor(20_000) { apState() == AP_ENABLED || apState() == AP_FAILED }
                if (apState() == AP_ENABLED) { usedSem = true; log("hotspot started (Samsung)"); return "" }
                log("Samsung switch didn't bring the hotspot up (state ${apState()}); trying Android's tethering API")
                switchOff()
                waitFor(10_000) { apState() == AP_DISABLED || apState() == AP_FAILED }
            }
        }
        usedSem = false
        return startTethering()
    }

    @Synchronized
    fun stop(): String = runCatching {
        switchOff()
        waitFor(10_000) { apState() == AP_DISABLED || apState() == AP_FAILED }
        log("stopped")
        ""
    }.getOrElse { it.rootMessage() }

    /** Whether the running hotspot was started through Samsung's switch (then it must be stopped the same way). */
    @Volatile private var usedSem = false

    private fun switchOff() {
        sem?.let { s -> runCatching { call(s, "setWifiApEnabled", null, false) } }
        if (apState() == AP_ENABLED || apState() == AP_ENABLING) stopTethering()
    }

    /** Status in the root engine's format (EngineStatus.parse): STATE/IFACE/UPSTREAM/FREQ lines, CLIENT lines, log. */
    fun status(groupId: String): String = buildString {
        val state = when (apState()) {
            AP_ENABLED -> "running"
            AP_ENABLING -> "starting"
            AP_FAILED -> "failed"
            else -> "stopped"
        }
        appendLine("STATE=$state")
        appendLine("IFACE=${tetheredIfaces.firstOrNull() ?: ""}")
        appendLine("UPSTREAM=$upstream")
        if (frequency > 0 && state == "running") appendLine("FREQ=$frequency")
        // What this phone's hotspot supports: client control (max devices, blocking), isolation, 5 GHz channels.
        val clientControl = supports(FEATURE_CLIENT_FORCE_DISCONNECT)
        val semMax = !semMaxClientsFailed && hasSem("setWifiApMaxClientToFramework", "setWifiApMaxClient")
        appendLine("SHZ_MAXCLIENTS=${if (clientControl || semMax) 1 else 0}")
        appendLine("SHZ_BLOCK=${if (clientControl) 1 else 0}")
        appendLine("SHZ_ISOLATION=${if (hasIsolation || (!semIsolationFailed && hasSem("setWifiApIsolate"))) 1 else 0}")
        // What the phone's hotspot is set to now (it can be changed in Android's settings behind SegAP's back).
        runCatching {
            val c = currentConfig()
            fun get(name: String) = c.javaClass.getMethod(name).invoke(c)
            appendLine("SHZ_CONF=" + ShizukuConfigPrint.of(get("getSsid") as String?, get("getPassphrase") as String?, get("isHiddenSsid") == true))
        }
        appendLine("SHZ_SAMSUNG=${if (sem != null) 1 else 0}")
        appendLine("SHZ_5GHZ=${channels5().joinToString(",")}")
        if (state == "running") {
            val macs = (associated + dhcp.keys).distinct()
            val now = System.currentTimeMillis() / 1000
            synchronized(firstSeen) {
                firstSeen.keys.retainAll(macs.toSet())
                macs.forEach { firstSeen.getOrPut(it) { now } }
            }
            macs.forEach { mac ->
                val (ip, host) = dhcp[mac] ?: Pair("-", "-")
                val since = synchronized(firstSeen) { firstSeen[mac] ?: now }
                appendLine("CLIENT $mac $groupId ${ip.ifBlank { "-" }} ${host.ifBlank { "-" }.replace(' ', '_')} $since 0 - - - -")
            }
        }
        appendLine("LOG_BEGIN")
        synchronized(log) { log.forEach { appendLine(it) } }
        appendLine("LOG_END")
    }

    fun close() {
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ tethering

    private fun startTethering(): String {
        val reqBuilder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        // No entitlement UI. Not "exempt from entitlement check": that needs NETWORK_STACK (Android 12 refuses the
        // whole request with TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION); TETHER_PRIVILEGED is enough otherwise.
        invokeBest(reqBuilder, "setShouldShowEntitlementUi", false)
        val request = call(reqBuilder, "build")!!
        val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val done = CountDownLatch(1)
        var error = ""
        val cb = proxy(cbClass) { m, args ->
            when (m.name) {
                "onTetheringStarted" -> done.countDown()
                "onTetheringFailed" -> { error = "Android refused to start the hotspot (error ${args?.getOrNull(0)})"; done.countDown() }
            }
        }
        tethering.javaClass.getMethod("startTethering", request.javaClass, Executor::class.java, cbClass)
            .invoke(tethering, request, executor, cb)
        if (!done.await(25, TimeUnit.SECONDS)) error = "The hotspot didn't start within 25 s"
        if (error.isNotEmpty()) return error
        // "Started" only means tethering accepted the request; the access point itself may still fail.
        waitFor(15_000) { apState() == AP_ENABLED || apState() == AP_FAILED }
        return when (apState()) {
            AP_ENABLED -> { log("hotspot started"); "" }
            AP_FAILED -> "The phone's Wi-Fi couldn't start the hotspot with these settings (try another channel or band)"
            else -> "The hotspot didn't come up within 15 s"
        }
    }

    // ------------------------------------------------------------------ capabilities

    private fun hasSem(vararg names: String): Boolean = sem != null && sem.javaClass.methods.any { it.name in names }

    private fun supports(feature: Long): Boolean =
        capability?.let { runCatching { call(it, "areFeaturesSupported", feature) as Boolean }.getOrNull() } ?: false

    /** 5 GHz channels the phone allows for a hotspot here (Android 12+; empty = unknown or none). */
    private fun channels5(): List<Int> = capability?.let { c ->
        runCatching { (call(c, "getSupportedChannelList", BAND_5GHZ) as IntArray).toList() }.getOrNull()
    } ?: emptyList()

    private fun stopTethering() {
        tethering.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tethering, TETHERING_WIFI)
    }

    private fun apState(): Int = call(wifi, "getWifiApState") as Int

    // ------------------------------------------------------------------ callbacks

    private fun register() {
        val tcb = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        val tetherCallback = proxy(tcb) { m, args ->
            when (m.name) {
                // Interface names, or (Android 12+) TetheringInterface objects with getType()/getInterface().
                "onTetheredInterfacesChanged" -> tetheredIfaces = (args?.getOrNull(0) as? Collection<*>).orEmpty().mapNotNull { i ->
                    when (i) {
                        is String -> i
                        null -> null
                        else -> runCatching {
                            if (call(i, "getType") as Int != TETHERING_WIFI) null else call(i, "getInterface") as String
                        }.getOrNull()
                    }
                }
                "onUpstreamChanged" -> upstream = (args?.getOrNull(0) as? Network)
                    ?.let { runCatching { connectivity?.getLinkProperties(it)?.interfaceName }.getOrNull() } ?: ""
                "onClientsChanged" -> dhcp = (args?.getOrNull(0) as? Collection<*>).orEmpty().mapNotNull { client ->
                    // Only Wi-Fi tethering's clients (USB/Bluetooth clients are listed too).
                    if (runCatching { call(client!!, "getTetheringType") as Int }.getOrDefault(TETHERING_WIFI) != TETHERING_WIFI) return@mapNotNull null
                    val mac = call(client!!, "getMacAddress").toString().lowercase()
                    val addr = (call(client, "getAddresses") as? List<*>).orEmpty().firstOrNull()
                    val ip = addr?.let { call(it, "getAddress")?.toString()?.substringBefore('/') } ?: "-"
                    val host = addr?.let { call(it, "getHostname") as? String } ?: "-"
                    mac to (ip to host)
                }.toMap()
                "onError" -> log("tethering error ${args?.getOrNull(1)} on ${args?.getOrNull(0)}")
            }
        }
        tethering.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, tcb).invoke(tethering, executor, tetherCallback)

        val scb = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
        val softApCallback = proxy(scb) { m, args ->
            when (m.name) {
                "onConnectedClientsChanged" -> {
                    // (List<WifiClient>) before Android 12, (SoftApInfo, List<WifiClient>) per AP instance after.
                    val list = args?.lastOrNull() as? List<*>
                    if (args?.size == 1 || m.parameterTypes.size == 1) associated = list.orEmpty().mapNotNull { macOf(it) }
                }
                "onInfoChanged" -> {
                    val info = (args?.getOrNull(0) as? List<*>)?.firstOrNull() ?: args?.getOrNull(0)
                    frequency = info?.let { runCatching { call(it, "getFrequency") as Int }.getOrNull() } ?: frequency
                }
                "onCapabilityChanged" -> capability = args?.getOrNull(0)
            }
        }
        wifi.javaClass.getMethod("registerSoftApCallback", Executor::class.java, scb).invoke(wifi, executor, softApCallback)
    }

    private fun macOf(wifiClient: Any?): String? = wifiClient?.let { runCatching { call(it, "getMacAddress").toString().lowercase() }.getOrNull() }

    // ------------------------------------------------------------------ helpers

    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(log) {
            log.addLast("$t $msg")
            while (log.size > 15) log.removeFirst()
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!cond() && SystemClock.elapsedRealtime() < end) Thread.sleep(200)
    }

    companion object {
        const val TETHERING_WIFI = 0
        /** SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT */
        const val FEATURE_CLIENT_FORCE_DISCONNECT = 1L shl 1
        const val BAND_2GHZ = 1
        const val BAND_5GHZ = 2
        const val SECURITY_WPA2 = 1
        const val AP_DISABLING = 10
        const val AP_DISABLED = 11
        const val AP_ENABLING = 12
        const val AP_ENABLED = 13
        const val AP_FAILED = 14
        private const val SHELL_PACKAGE = "com.android.shell"

        /** A Context of the shell package in this (app_process) process: no app is attached, so build one. */
        private fun shellContext(): Context {
            if (Looper.myLooper() == null) Looper.prepare()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getDeclaredConstructor().let {
                it.isAccessible = true
                it.newInstance().also { t ->
                    at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, t)
                    // Android 12+: attach() would create the ConfigurationController; Samsung's display sandboxing
                    // (Android 16) asks for the configuration while the system context is built.
                    runCatching {
                        val cc = Class.forName("android.app.ConfigurationController")
                        val controller = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                            .apply { isAccessible = true }.newInstance(t)
                        at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(t, controller)
                        // ...and the configuration itself must not be null.
                        cc.getDeclaredMethod("setConfiguration", android.content.res.Configuration::class.java)
                            .apply { isAccessible = true }.invoke(controller, android.content.res.Configuration().apply { setToDefaults() })
                    }
                }
            }
            val system = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
            return ShellContext(system.createPackageContext(SHELL_PACKAGE, 0))
        }

        /**
         * A package context made from the system context still reports the op package "android"; services check the
         * calling package against the calling uid (2000), so report the shell package and uid.
         */
        private class ShellContext(base: Context) : android.content.ContextWrapper(base) {
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getOpPackageName(): String = SHELL_PACKAGE
            override fun getAttributionTag(): String? = null
            override fun getApplicationContext(): Context = this
            override fun getAttributionSource(): android.content.AttributionSource =
                android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        }

        private fun binder(name: String): android.os.IBinder =
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as? android.os.IBinder
                ?: error("system service \"$name\" not found")

        /** WifiManager(Context, IWifiManager[, Looper]) - the Looper argument exists up to Android 12/13. */
        private fun wifiManager(context: Context, looper: Looper): Any {
            val service = Class.forName("android.net.wifi.IWifiManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder(Context.WIFI_SERVICE))
            val ctor = android.net.wifi.WifiManager::class.java.declaredConstructors
                .filter { it.parameterTypes.firstOrNull() == Context::class.java }.maxBy { it.parameterTypes.size }
            ctor.isAccessible = true
            return if (ctor.parameterTypes.size >= 3) ctor.newInstance(context, service, looper) else ctor.newInstance(context, service)
        }

        /** TetheringManager(Context, Supplier<IBinder>) with the "tethering" connector service. */
        private fun tetheringManager(context: Context): Any {
            val ctor = Class.forName("android.net.TetheringManager")
                .getDeclaredConstructor(Context::class.java, java.util.function.Supplier::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(context, java.util.function.Supplier { binder("tethering") })
        }

        private fun call(target: Any, name: String, vararg args: Any?): Any? = invokeBest(target, name, *args)

        /** Invokes the public method [name] whose parameters accept [args] (boxed primitives match primitive types). */
        fun invokeBest(target: Any, name: String, vararg args: Any?): Any? {
            val m = target.javaClass.methods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.withIndex().all { (i, t) -> args[i] == null || box(t).isInstance(args[i]) }
            } ?: throw NoSuchMethodException("${target.javaClass.name}.$name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})")
            return m.invoke(target, *args)
        }

        private fun hasMethod(target: Any, name: String) = target.javaClass.methods.any { it.name == name }

        private fun box(t: Class<*>): Class<*> = when (t) {
            Int::class.javaPrimitiveType -> Integer::class.java
            Boolean::class.javaPrimitiveType -> java.lang.Boolean::class.java
            Long::class.javaPrimitiveType -> java.lang.Long::class.java
            else -> t
        }

        /** Implements a (callback) interface: [onCall] gets every method; Object's methods behave normally. */
        private fun proxy(iface: Class<*>, onCall: (Method, Array<out Any?>?) -> Unit): Any {
            lateinit var self: Any
            self = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), InvocationHandler { _, m, args ->
                when (m.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    "toString" -> "SegAP${iface.simpleName}"
                    else -> { runCatching { onCall(m, args) }; null }
                }
            })
            return self
        }

        private fun Throwable.rootCause(): Throwable {
            var t: Throwable = this
            while (t.cause != null && (t is java.lang.reflect.InvocationTargetException || t.message == null)) t = t.cause!!
            return t
        }

        private fun Throwable.rootMessage(): String = rootCause().let { "${it.javaClass.simpleName}: ${it.message}" }

        /**
         * Test entry, run as the shell user:
         *   CLASSPATH=<apk> app_process /system/bin com.oshri.multihotspot.shizuku.HotspotCore status|stop|start <ssid> <pass> <2|5> <ch> <hidden> <max>
         */
        @JvmStatic
        fun main(args: Array<String>) {
            Looper.prepareMainLooper()
            val core = HotspotCore()
            Thread.sleep(1500) // let the callbacks report the current state
            when (args.getOrNull(0)) {
                "start" -> println("start: '${core.start(args[1], args[2], args[3] == "5", args[4].toInt(), args[5] == "1",
                    args[6].toInt(), emptyList(), false)}'")
                "stop" -> println("stop: '${core.stop()}'")
                "trial" -> { println(core.trial(args.getOrNull(1)?.toIntOrNull() ?: 20)); core.close(); kotlin.system.exitProcess(0) }
            }
            Thread.sleep(1500)
            print(core.status("g1"))
            core.close()
            kotlin.system.exitProcess(0)
        }
    }
}
