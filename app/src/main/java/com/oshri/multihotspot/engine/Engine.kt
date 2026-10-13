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

    /** md5 of the config files last written for the engine, in name order (matches the engine's CFGSUM). */
    fun writtenConfigSum(): String? {
        val files = cfgDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: return null
        if (files.isEmpty()) return null
        val md = java.security.MessageDigest.getInstance("MD5")
        files.forEach { md.update(it.readBytes()) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ---- Tailscale instances for exit nodes (one per group; see mh_engine.sh)

    /** Installs the downloaded official package (the engine checks [sha256] again). Returns the version or an error. */
    suspend fun tailscaleInstall(tgz: File, sha256: String): Result<String> {
        installScript()
        val r = RootShell.run("sh ${quote(script.absolutePath)} ts-install ${quote(tgz.absolutePath)} ${quote(sha256)}", 300_000)
        val ok = r.output.lineSequence().firstOrNull { it.startsWith("OK ") }
        return if (ok != null) Result.success(ok.removePrefix("OK ").trim())
        else Result.failure(IllegalStateException(r.output.lineSequence().firstOrNull { it.startsWith("ERROR") }?.removePrefix("ERROR:")?.trim()
            ?: r.output.takeLast(300).ifBlank { "install failed" }))
    }

    /** Starts the group's instance if needed (and a login if it isn't signed in), then reports its state. */
    suspend fun tailscaleUp(groupId: String, hostname: String): com.oshri.multihotspot.model.TailscaleInstance {
        installScript()
        return com.oshri.multihotspot.model.TailscaleInstance.parse(
            RootShell.run("sh ${quote(script.absolutePath)} ts-up ${quote(groupId)} ${quote(hostname)}", 90_000).output
        )
    }

    suspend fun tailscaleStatus(groupId: String): com.oshri.multihotspot.model.TailscaleInstance {
        installScript()
        return com.oshri.multihotspot.model.TailscaleInstance.parse(
            RootShell.run("sh ${quote(script.absolutePath)} ts-status ${quote(groupId)}", 30_000).output
        )
    }

    suspend fun tailscaleDown(groupId: String) {
        installScript()
        RootShell.run("sh ${quote(script.absolutePath)} ts-down ${quote(groupId)}", 30_000)
    }

    /**
     * Stops instances nothing needs any more (all of them while the hotspot is off), and signs out the ones of
     * networks not in [knownGroupIds] (a new network that was never saved).
     */
    suspend fun tailscaleGc(knownGroupIds: Collection<String>) {
        installScript()
        RootShell.run("sh ${quote(script.absolutePath)} ts-gc ${quote(knownGroupIds.joinToString(","))}", 120_000)
    }

    /** Signs the group's instance out of the tailnet and deletes its state. */
    suspend fun tailscaleLogout(groupId: String) {
        installScript()
        RootShell.run("sh ${quote(script.absolutePath)} ts-logout ${quote(groupId)}", 90_000)
    }

    /** Last lines of hostapd's own log, for diagnosing start failures. */
    suspend fun hostapdLog(): String =
        RootShell.run("logcat -d -s hostapd:* | grep -i -E 'fail|error|invalid|not permitted|busy|AP-' | tail -n 12", 15_000).output
}
