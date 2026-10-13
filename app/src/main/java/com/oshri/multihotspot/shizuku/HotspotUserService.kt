package com.oshri.multihotspot.shizuku

import android.content.Context
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * The Shizuku engine's service. Shizuku starts it in its own process with the ADB shell user's rights
 * (app_process, uid 2000) and hands its binder to the app; all work is done by [HotspotCore].
 */
class HotspotUserService() : IHotspotService.Stub() {

    /** Shizuku 13+ passes a Context; it isn't needed (HotspotCore builds the shell's own). */
    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private val core by lazy { HotspotCore() }

    override fun destroy() {
        runCatching { core.close() }
        exitProcess(0)
    }

    override fun start(ssid: String, passphrase: String, band5: Boolean, channel: Int, hidden: Boolean, maxClients: Int,
                       blocked: List<String>, isolate: Boolean): String =
        core.start(ssid, passphrase, band5, channel, hidden, maxClients, blocked, isolate)

    override fun stop(): String = core.stop()

    override fun status(groupId: String): String = core.status(groupId)
}
