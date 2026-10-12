package com.oshri.multihotspot.model

/**
 * One password on the shared SSID. Devices that join with [password] land in this group's LAN.
 * [id] is the hostapd keyid and engine tag: lowercase [a-z0-9]+, fixed once created.
 */
data class Group(
    val id: String,
    val name: String,
    val password: String,
    val gateway: String,
    val prefix: Int,
    val dhcpStart: String,
    val dhcpEnd: String,
    val leaseHours: Int = 12,
    val internet: Boolean = true,
    val devicesSeeEachOther: Boolean = true,
    val dns1: String = "1.1.1.1",
    val dns2: String = "8.8.8.8",
) {
    val networkCidr: String
        get() {
            val gw = Ipv4.parse(gateway) ?: return ""
            return "${Ipv4.format(Ipv4.network(gw, prefix))}/$prefix"
        }

    val netmask: String get() = Ipv4.format(Ipv4.mask(prefix))
}

/** A fixed address for one device inside one group. */
data class StaticLease(
    val mac: String,
    val groupId: String,
    val ip: String,
    val label: String = "",
)

enum class WifiMode { KEEP, OFF }

data class HotspotConfig(
    val ssid: String,
    val channel: Int,
    val hidden: Boolean = false,
    val maxClients: Int = 10,
    val bssid: String,
    val iface: String = "swlan0",
    val wifiMode: WifiMode = WifiMode.KEEP,
    /** When false, devices may talk to each other directly over Wi-Fi (needed for casting/discovery), weaker isolation. */
    val strictIsolation: Boolean = true,
    val groups: List<Group>,
    val staticLeases: List<StaticLease> = emptyList(),
    val blockedMacs: List<String> = emptyList(),
) {
    fun group(id: String): Group? = groups.firstOrNull { it.id == id }

    fun nextGroupId(): String {
        var n = groups.size + 1
        while (groups.any { it.id == "g$n" }) n++
        return "g$n"
    }

    companion object {
        val CHANNELS_24GHZ = (1..13).toList()

        fun default(): HotspotConfig = HotspotConfig(
            ssid = "S10-Hotspot",
            channel = 6,
            bssid = Mac.randomLocal(),
            groups = listOf(
                Group(
                    id = "g1", name = "Home", password = Passwords.generate(),
                    gateway = "192.168.10.1", prefix = 24,
                    dhcpStart = "192.168.10.100", dhcpEnd = "192.168.10.200",
                    internet = true, devicesSeeEachOther = true,
                ),
                Group(
                    id = "g2", name = "Guest", password = Passwords.generate(),
                    gateway = "192.168.20.1", prefix = 24,
                    dhcpStart = "192.168.20.100", dhcpEnd = "192.168.20.200",
                    leaseHours = 2, internet = true, devicesSeeEachOther = false,
                ),
            ),
        )
    }
}

/** Suggests the next free 192.168.N.0/24 block for a new group. */
fun HotspotConfig.suggestNewGroup(name: String): Group {
    val used = groups.mapNotNull { Ipv4.parse(it.gateway) }.map { (it shr 8) and 255 }.toSet()
    val third = (10..250 step 10).firstOrNull { it.toLong() !in used } ?: (1..254).first { it.toLong() !in used }
    return Group(
        id = nextGroupId(), name = name, password = Passwords.generate(),
        gateway = "192.168.$third.1", prefix = 24,
        dhcpStart = "192.168.$third.100", dhcpEnd = "192.168.$third.200",
    )
}

object Passwords {
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(length: Int = 12): String {
        val r = java.security.SecureRandom()
        return (1..length).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }
}

// ------------------------------------------------------------------ runtime status

enum class EngineState { STOPPED, STARTING, RUNNING, FAILED, UNKNOWN }

data class Client(
    val mac: String,
    val groupId: String,
    val ip: String?,
    val hostname: String?,
    val connectedSinceEpoch: Long,
    val uploadBytes: Long,
)

data class EngineStatus(
    val state: EngineState = EngineState.UNKNOWN,
    val iface: String = "",
    val upstream: String = "",
    val hostapdAlive: Boolean = false,
    val dhcpAlive: Boolean = false,
    val daemonAlive: Boolean = false,
    val clients: List<Client> = emptyList(),
    val log: List<String> = emptyList(),
) {
    companion object {
        fun parse(output: String): EngineStatus {
            var s = EngineStatus()
            val clients = mutableListOf<Client>()
            val log = mutableListOf<String>()
            var inLog = false
            for (raw in output.lineSequence()) {
                val line = raw.trimEnd()
                when {
                    line == "LOG_BEGIN" -> inLog = true
                    line == "LOG_END" -> inLog = false
                    inLog -> if (line.isNotBlank()) log += line
                    line.startsWith("CLIENT ") -> {
                        // CLIENT <mac> <group> <ip|-> <hostname|-> <since-epoch> <upload-bytes>
                        val p = line.split(" ")
                        if (p.size >= 7) clients += Client(
                            mac = p[1], groupId = p[2],
                            ip = p[3].takeIf { it != "-" }, hostname = p[4].takeIf { it != "-" && it != "*" },
                            connectedSinceEpoch = p[5].toLongOrNull() ?: 0, uploadBytes = p[6].toLongOrNull() ?: 0,
                        )
                    }
                    line.contains('=') -> {
                        val (k, v) = line.split('=', limit = 2)
                        s = when (k) {
                            "STATE" -> s.copy(state = when (v) {
                                "running" -> EngineState.RUNNING
                                "starting" -> EngineState.STARTING
                                "failed" -> EngineState.FAILED
                                "stopped" -> EngineState.STOPPED
                                else -> EngineState.UNKNOWN
                            })
                            "IFACE" -> s.copy(iface = v)
                            "UPSTREAM" -> s.copy(upstream = v)
                            "HOSTAPD" -> s.copy(hostapdAlive = v == "1")
                            "DHCP" -> s.copy(dhcpAlive = v == "1")
                            "DAEMON" -> s.copy(daemonAlive = v == "1")
                            else -> s
                        }
                    }
                }
            }
            return s.copy(clients = clients, log = log)
        }
    }
}
