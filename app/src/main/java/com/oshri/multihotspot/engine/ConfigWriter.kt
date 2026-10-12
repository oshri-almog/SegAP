package com.oshri.multihotspot.engine

import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.Mac
import com.oshri.multihotspot.model.WifiMode
import java.io.File

/** Renders a validated [HotspotConfig] into the files mh_engine.sh expects. */
object ConfigWriter {

    /** Where the engine copies hostapd's files (must be readable by the hostapd SELinux domain). */
    const val HOSTAPD_DIR = "/data/vendor/wifi/hostapd/mh"

    fun engineConf(c: HotspotConfig, wlHelper: String = ""): String = buildString {
        appendLine("IFACE=${c.iface}")
        appendLine("MAC=${Mac.normalize(c.bssid) ?: ""}")
        appendLine("WIFI_MODE=${if (c.wifiMode == WifiMode.OFF) "off" else "keep"}")
        appendLine("UPSTREAM=auto")
        appendLine("DEFAULT_GROUP=${defaultGroupId(c)}")
        if (wlHelper.isNotBlank()) appendLine("WL_HELPER=$wlHelper")
    }

    /** Devices without a keyid (should not happen with wpa_psk_file) go to the most restricted group. */
    fun defaultGroupId(c: HotspotConfig): String =
        (c.groups.firstOrNull { !it.internet } ?: c.groups.firstOrNull { !it.devicesSeeEachOther } ?: c.groups.last()).id

    fun hostapdConf(c: HotspotConfig, countryCode: String): String = buildString {
        appendLine("interface=${c.iface}")
        appendLine("driver=nl80211")
        appendLine("ctrl_interface=/data/vendor/wifi/hostapd/ctrl")
        appendLine("country_code=$countryCode")
        appendLine("hw_mode=g")
        appendLine("channel=${c.channel}")
        appendLine("ieee80211n=1")
        appendLine("wmm_enabled=1")
        // Sleeping clients wake for buffered traffic every DTIM beacon: 1 = every 100 ms (driver default 2 = 200 ms).
        appendLine("beacon_int=100")
        appendLine("dtim_period=1")
        appendLine("max_num_sta=${c.maxClients}")
        appendLine("ap_isolate=${if (c.strictIsolation) 1 else 0}")
        appendLine("ignore_broadcast_ssid=${if (c.hidden) 1 else 0}")
        appendLine("ssid2=${hex(c.ssid)}")
        appendLine("utf8_ssid=1")
        appendLine("wpa=2")
        appendLine("wpa_key_mgmt=WPA-PSK")
        appendLine("rsn_pairwise=CCMP")
        appendLine("wpa_psk_file=$HOSTAPD_DIR/psk.txt")
        if (c.blockedMacs.isNotEmpty()) {
            appendLine("macaddr_acl=0")
            appendLine("deny_mac_file=$HOSTAPD_DIR/deny.txt")
        }
    }

    fun pskFile(c: HotspotConfig): String = buildString {
        c.groups.forEach { appendLine("keyid=${it.id} 00:00:00:00:00:00 ${it.password}") }
    }

    fun groupsConf(c: HotspotConfig): String = buildString {
        c.groups.forEach { g ->
            val dns = listOf(g.dns1, g.dns2).filter { it.isNotBlank() }.joinToString(",")
            appendLine(
                listOf(
                    g.id, g.gateway, g.prefix, g.networkCidr, g.netmask,
                    if (g.internet) 1 else 0, if (g.devicesSeeEachOther) 0 else 1,
                    g.dhcpStart, g.dhcpEnd, "${g.leaseHours}h", dns,
                    if (g.tailscale) 1 else 0,
                    g.canReach.filter { id -> id != g.id && c.groups.any { it.id == id } }.joinToString(",").ifEmpty { "-" },
                ).joinToString(" ")
            )
        }
    }

    fun staticConf(c: HotspotConfig): String = buildString {
        c.staticLeases.forEach { l -> Mac.normalize(l.mac)?.let { appendLine("$it ${l.groupId} ${l.ip}") } }
    }

    fun denyFile(c: HotspotConfig): String = buildString {
        c.blockedMacs.mapNotNull { Mac.normalize(it) }.forEach { appendLine(it) }
    }

    /** Writes all engine files into [dir] (replacing its previous content). */
    fun writeAll(c: HotspotConfig, countryCode: String, dir: File, wlHelper: String = "") {
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, "engine.conf").writeText(engineConf(c, wlHelper))
        File(dir, "hostapd.conf").writeText(hostapdConf(c, countryCode))
        File(dir, "psk.txt").writeText(pskFile(c))
        File(dir, "groups.conf").writeText(groupsConf(c))
        File(dir, "static.conf").writeText(staticConf(c))
        if (c.blockedMacs.isNotEmpty()) File(dir, "deny.txt").writeText(denyFile(c))
    }

    private fun hex(s: String): String = s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
