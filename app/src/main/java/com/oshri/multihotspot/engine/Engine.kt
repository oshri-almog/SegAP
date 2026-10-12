package com.oshri.multihotspot.engine

import android.content.Context
import com.oshri.multihotspot.engine.RootShell.quote
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import java.io.File

/** Kotlin side of mh_engine.sh: installs the script, writes its config and calls it through [RootShell]. */
class Engine(private val context: Context) {

    private val script = File(context.filesDir, "mh_engine.sh")
    private val cfgDir = File(context.filesDir, "cfg")

    private var scriptChecked = false

    /**
     * Makes filesDir/mh_engine.sh match the app's copy. Runs at every start and once per process otherwise, so
     * after an app update even status/stop use the new script. Written via rename so a running sh keeps reading
     * the old file.
     */
    private fun installScript(force: Boolean = false) {
        if (scriptChecked && !force && script.exists()) return
        // Strip CR defensively: a CRLF script fails in Android's shell with confusing errors.
        val text = context.assets.open("mh_engine.sh").use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r", "")
        if (!script.exists() || script.readText() != text) {
            val tmp = File(script.parentFile, "mh_engine.sh.tmp")
            tmp.writeText(text)
            tmp.renameTo(script)
        }
        scriptChecked = true
    }

    /** Regulatory country the Wi-Fi driver is currently using (never chosen by the user). */
    private suspend fun countryCode(): String? {
        val r = RootShell.run("cmd wifi get-country-code", 10_000)
        // "Wifi Country Code = IL"
        return Regex("\\b([A-Z]{2})\\b").find(r.output.substringAfterLast("="))?.groupValues?.get(1)
    }

    /** Native helper for per-device radio stats, extracted from the APK (jniLibs) by the installer. */
    private fun wlHelper(): String =
        File(context.applicationInfo.nativeLibraryDir, "libmhwl.so").takeIf { it.exists() }?.absolutePath ?: ""

    suspend fun start(config: HotspotConfig): RootShell.Result {
        installScript(force = true)
        val cc = countryCode() ?: "IL"
        ConfigWriter.writeAll(config, cc, cfgDir, wlHelper())
        return RootShell.run("sh ${quote(script.absolutePath)} start ${quote(cfgDir.absolutePath)}", 120_000)
    }

    suspend fun stop(): RootShell.Result {
        installScript()
        return RootShell.run("sh ${quote(script.absolutePath)} stop", 60_000)
    }

    suspend fun status(): EngineStatus {
        installScript()
        val wl = wlHelper()
        val r = RootShell.run("sh ${quote(script.absolutePath)} status" + (if (wl.isNotEmpty()) " ${quote(wl)}" else ""), 20_000)
        if (r.exitCode < 0) return EngineStatus(state = EngineState.UNKNOWN, log = listOf(r.output))
        return EngineStatus.parse(r.output)
    }

    /** Last lines of hostapd's own log, for diagnosing start failures. */
    suspend fun hostapdLog(): String =
        RootShell.run("logcat -d -s hostapd:* | grep -i -E 'fail|error|invalid|not permitted|busy|AP-' | tail -n 12", 15_000).output
}
