package com.oshri.multihotspot.data

import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.HotspotProfile
import com.oshri.multihotspot.model.MacMode
import com.oshri.multihotspot.model.StaticLease
import com.oshri.multihotspot.model.WifiMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists [HotspotConfig] as JSON in the app's private files dir. */
class ConfigStore(private val file: File) {

    fun load(): HotspotConfig {
        if (!file.exists()) return HotspotConfig.default().also { save(it) }
        return try {
            fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".broken"))
            HotspotConfig.default().also { save(it) }
        }
    }

    fun save(c: HotspotConfig) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(c).toString(2))
        tmp.renameTo(file)
    }

    companion object {
        fun toJson(c: HotspotConfig): JSONObject = JSONObject().apply {
            put("ssid", c.ssid)
            put("channel", c.channel)
            put("hidden", c.hidden)
            put("maxClients", c.maxClients)
            put("bssid", c.bssid)
            put("iface", c.iface)
            put("wifiMode", c.wifiMode.name)
            put("strictIsolation", c.strictIsolation)
            put("groups", JSONArray().apply {
                c.groups.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id); put("name", g.name); put("password", g.password)
                        put("gateway", g.gateway); put("prefix", g.prefix)
                        put("dhcpStart", g.dhcpStart); put("dhcpEnd", g.dhcpEnd)
                        put("leaseHours", g.leaseHours); put("internet", g.internet)
                        put("devicesSeeEachOther", g.devicesSeeEachOther)
                        put("dns1", g.dns1); put("dns2", g.dns2)
                        put("tailscale", g.tailscale)
                        put("canReach", JSONArray(g.canReach))
                        put("exitNodeIp", g.exitNodeIp); put("exitNodeName", g.exitNodeName)
                        put("shareSubnet", g.shareSubnet)
                        put("enabled", g.enabled)
                    })
                }
            })
            put("staticLeases", JSONArray().apply {
                c.staticLeases.forEach { l ->
                    put(JSONObject().apply { put("mac", l.mac); put("groupId", l.groupId); put("ip", l.ip); put("label", l.label) })
                }
            })
            put("blockedMacs", JSONArray(c.blockedMacs))
            put("band5", c.band5)
            put("channel5", c.channel5)
            put("txPower", c.txPower); put("bandwidth40", c.bandwidth40); put("macMode", c.macMode.name)
            put("profiles", JSONArray().apply {
                c.profiles.forEach { pr ->
                    put(JSONObject().apply {
                        put("id", pr.id); put("name", pr.name); put("ssid", pr.ssid); put("channel", pr.channel)
                        put("hidden", pr.hidden); put("maxClients", pr.maxClients); put("bssid", pr.bssid)
                        put("iface", pr.iface); put("wifiMode", pr.wifiMode.name); put("strictIsolation", pr.strictIsolation)
                        put("band5", pr.band5); put("channel5", pr.channel5)
                        put("txPower", pr.txPower); put("bandwidth40", pr.bandwidth40); put("macMode", pr.macMode.name)
                    })
                }
            })
            put("activeProfile", c.activeProfile)
        }

        private fun macMode(s: String) = runCatching { MacMode.valueOf(s) }.getOrDefault(MacMode.RANDOM)

        fun fromJson(o: JSONObject): HotspotConfig {
            val def = HotspotConfig.default()
            val groups = o.optJSONArray("groups")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val g = arr.getJSONObject(i)
                    Group(
                        id = g.getString("id"), name = g.getString("name"), password = g.getString("password"),
                        gateway = g.getString("gateway"), prefix = g.getInt("prefix"),
                        dhcpStart = g.getString("dhcpStart"), dhcpEnd = g.getString("dhcpEnd"),
                        leaseHours = g.optInt("leaseHours", 12), internet = g.optBoolean("internet", true),
                        devicesSeeEachOther = g.optBoolean("devicesSeeEachOther", true),
                        dns1 = g.optString("dns1", "1.1.1.1"), dns2 = g.optString("dns2", ""),
                        tailscale = g.optBoolean("tailscale", false),
                        canReach = g.optJSONArray("canReach")?.let { r -> (0 until r.length()).map { r.getString(it) } } ?: emptyList(),
                        exitNodeIp = g.optString("exitNodeIp", ""), exitNodeName = g.optString("exitNodeName", ""),
                        shareSubnet = g.optBoolean("shareSubnet", false),
                        enabled = g.optBoolean("enabled", true),
                    )
                }
            } ?: def.groups
            val leases = o.optJSONArray("staticLeases")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    StaticLease(l.getString("mac"), l.getString("groupId"), l.getString("ip"), l.optString("label", ""))
                }
            } ?: emptyList()
            val profiles = o.optJSONArray("profiles")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    HotspotProfile(
                        id = p.getString("id"), name = p.getString("name"), ssid = p.getString("ssid"),
                        channel = p.optInt("channel", def.channel), hidden = p.optBoolean("hidden", false),
                        maxClients = p.optInt("maxClients", def.maxClients), bssid = p.optString("bssid", def.bssid),
                        iface = p.optString("iface", def.iface),
                        wifiMode = runCatching { WifiMode.valueOf(p.optString("wifiMode", "KEEP")) }.getOrDefault(WifiMode.KEEP),
                        strictIsolation = p.optBoolean("strictIsolation", true),
                        band5 = p.optBoolean("band5", false), channel5 = p.optInt("channel5", 0),
                        txPower = p.optInt("txPower", 0), bandwidth40 = p.optBoolean("bandwidth40", false),
                        macMode = macMode(p.optString("macMode")),
                    )
                }
            } ?: emptyList()
            val blocked = o.optJSONArray("blockedMacs")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            return HotspotConfig(
                ssid = o.optString("ssid", def.ssid),
                channel = o.optInt("channel", def.channel),
                hidden = o.optBoolean("hidden", false),
                maxClients = o.optInt("maxClients", def.maxClients),
                bssid = o.optString("bssid", def.bssid),
                iface = o.optString("iface", def.iface),
                wifiMode = runCatching { WifiMode.valueOf(o.optString("wifiMode", "KEEP")) }.getOrDefault(WifiMode.KEEP),
                strictIsolation = o.optBoolean("strictIsolation", true),
                groups = groups,
                staticLeases = leases,
                blockedMacs = blocked,
                band5 = o.optBoolean("band5", false),
                channel5 = o.optInt("channel5", 0),
                txPower = o.optInt("txPower", 0),
                bandwidth40 = o.optBoolean("bandwidth40", false),
                macMode = macMode(o.optString("macMode")),
                profiles = profiles,
                activeProfile = o.optString("activeProfile", ""),
            )
        }
    }
}
