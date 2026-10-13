package com.oshri.multihotspot.engine

import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.PhoneHotspotSupport

/** How SegAP runs the hotspot. */
enum class BackendKind(val label: String) {
    /** Root (Magisk): SegAP's own access point, networks, firewall and Tailscale instances. */
    ROOT("Root"),

    /** Shizuku (ADB-level rights, no root): the phone's own hotspot, configured and started by SegAP. */
    SHIZUKU("Shizuku"),
}

/** Which backend the user wants; AUTO = root when available, Shizuku otherwise. */
enum class EngineMode { AUTO, ROOT, SHIZUKU }

/** What the active backend can do. The UI greys out the rest. */
data class Capabilities(
    /** Several passwords, each its own network. Otherwise only the first network is used. */
    val multipleNetworks: Boolean,
    /** Gateway, prefix, DHCP range, lease time and DNS per network (otherwise Android chooses). */
    val addressing: Boolean,
    /** Internet on/off, access between networks, strict isolation. */
    val firewall: Boolean,
    /** Devices in a network can't reach each other. */
    val deviceIsolation: Boolean,
    val fixedIps: Boolean,
    val blocking: Boolean,
    val maxClients: Boolean,
    /** Tailscale access, exit nodes and shared networks. */
    val tailscale: Boolean,
    /** Per-device signal, link rate and traffic. */
    val deviceStats: Boolean,
    /** 5 GHz channels the hotspot may use (empty = 2.4 GHz only). */
    val channels5: List<Int>,
    /** BSSID, radio interface and what happens to the phone's own Wi-Fi. */
    val radioDetails: Boolean,
) {
    val band5GHz: Boolean get() = channels5.isNotEmpty()

    companion object {
        val ROOT = Capabilities(
            multipleNetworks = true, addressing = true, firewall = true, deviceIsolation = true, fixedIps = true,
            blocking = true, maxClients = true, tailscale = true, deviceStats = true, channels5 = emptyList(), radioDetails = true,
        )

        /** Shizuku: the phone's own hotspot; [phone] says what this phone's hotspot supports (null = not known yet). */
        fun shizuku(phone: PhoneHotspotSupport?) = Capabilities(
            multipleNetworks = false, addressing = false, firewall = false, deviceIsolation = phone?.isolation ?: false,
            fixedIps = false, blocking = phone?.blocking ?: false, maxClients = phone?.maxClients ?: false, tailscale = false,
            deviceStats = false, channels5 = phone?.channels5 ?: emptyList(), radioDetails = false,
        )

        /** No backend yet: nothing is greyed out (the setup card explains what's missing). */
        val NONE = ROOT
    }
}

data class BackendResult(val ok: Boolean, val message: String = "")

interface HotspotBackend {
    val kind: BackendKind

    /** Starts (or restarts, applying changes) the hotspot with [config]. */
    suspend fun start(config: HotspotConfig): BackendResult

    suspend fun stop(): BackendResult

    suspend fun status(): EngineStatus

    /** Recent low-level log lines for diagnosing a failed start (radio log, service log). */
    suspend fun diagnostics(): String
}
