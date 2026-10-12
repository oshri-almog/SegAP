package com.oshri.multihotspot.data

import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.HotspotConfig
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
                    })
                }
            })
            put("staticLeases", JSONArray().apply {
                c.staticLeases.forEach { l ->
                    put(JSONObject().apply { put("mac", l.mac); put("groupId", l.groupId); put("ip", l.ip); put("label", l.label) })
                }
            })
            put("blockedMacs", JSONArray(c.blockedMacs))
        }

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
                    )
                }
            } ?: def.groups
            val leases = o.optJSONArray("staticLeases")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val l = arr.getJSONObject(i)
                    StaticLease(l.getString("mac"), l.getString("groupId"), l.getString("ip"), l.optString("label", ""))
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
            )
        }
    }
}
