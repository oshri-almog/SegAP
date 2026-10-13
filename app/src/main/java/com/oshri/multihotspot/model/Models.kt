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
    /** Devices in this group can reach the Tailscale network the phone is signed in to. */
    val tailscale: Boolean = false,
    /** Ids of other groups whose devices this group may open connections to (one-way; replies are allowed). */
    val canReach: List<String> = emptyList(),
    /**
     * Tailscale exit node (its 100.x address) this group's internet goes out through, via a Tailscale instance
     * SegAP runs for this group alone. Empty = normal internet. While the node is unreachable the group uses
     * the normal internet connection.
     */
    val exitNodeIp: String = "",
    /** How Tailscale names [exitNodeIp], for display only. */
    val exitNodeName: String = "",
    /**
     * Share this group with the tailnet: its Tailscale instance advertises the group's subnet as a subnet route,
     * so tailnet devices reach this group's devices (once the route is approved in the tailnet).
     */
    val shareSubnet: Boolean = false,
) {
    val usesExitNode: Boolean get() = exitNodeIp.isNotBlank()

    /** SegAP runs a Tailscale instance for this group (exit node and/or shared subnet). */
    val hasTailscaleInstance: Boolean get() = usesExitNode || shareSubnet

    /** Device name of this group's Tailscale instance in the tailnet: "segap-" + the network's name. */
    val tailscaleHostname: String
        get() {
            // Trim after truncating: a DNS label can't end with '-'.
            val slug = name.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-').take(50).trim('-')
            return "segap-" + slug.ifEmpty { id }
        }

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
    /** 5 GHz instead of 2.4 GHz (Shizuku engine only: the phone's own hotspot). */
    val band5: Boolean = false,
    /** 5 GHz channel, 0 = let the phone choose. */
    val channel5: Int = 0,
) {
    fun group(id: String): Group? = groups.firstOrNull { it.id == id }

    fun nextGroupId(): String {
        var n = groups.size + 1
        while (groups.any { it.id == "g$n" }) n++
        return "g$n"
    }

    companion object {
        val CHANNELS_24GHZ = (1..13).toList()
        /** Non-DFS 5 GHz channels (DFS channels make the hotspot wait for radar checks or move). */
        val CHANNELS_5GHZ = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)

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

/** Order-insensitive form, so re-saving the same settings doesn't look like a change. 5 GHz is Shizuku-only. */
fun HotspotConfig.normalized(): HotspotConfig = copy(
    staticLeases = staticLeases.sortedWith(compareBy({ it.groupId }, { it.mac })),
    blockedMacs = blockedMacs.map { it.lowercase() }.sorted(),
    band5 = false, channel5 = 0,
)

/**
 * The part of the config the Shizuku engine uses: Wi-Fi name, the first network's password and isolation, radio
 * settings, block list. Changes elsewhere don't need Apply there.
 */
fun HotspotConfig.shizukuView(): HotspotConfig = copy(
    groups = groups.take(1).map { g ->
        Group(id = g.id, name = "", password = g.password, gateway = "", prefix = 0, dhcpStart = "", dhcpEnd = "",
            devicesSeeEachOther = g.devicesSeeEachOther)
    },
    staticLeases = emptyList(), bssid = "", iface = "", wifiMode = WifiMode.KEEP, strictIsolation = true,
    channel = if (band5) 0 else channel, channel5 = if (band5) channel5 else 0,
    blockedMacs = blockedMacs.map { it.lowercase() }.sorted(),
)

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
    /** Bytes the device sent (its upload) this connection. */
    val uploadBytes: Long,
    /** Bytes sent to the device (its download) this connection; null if the driver doesn't report it. */
    val downloadBytes: Long? = null,
    /** Average received signal strength in dBm; null if unknown. */
    val rssi: Int? = null,
    /** PHY rate of the last frame phone -> device / device -> phone, kbit/s. */
    val linkDownKbps: Long? = null,
    val linkUpKbps: Long? = null,
    /** Current throughput in bytes/s, computed by the app from successive samples. */
    val uploadRate: Long = 0,
    val downloadRate: Long = 0,
) {
    val totalBytes: Long get() = uploadBytes + (downloadBytes ?: 0)
}

data class EngineStatus(
    val state: EngineState = EngineState.UNKNOWN,
    /** Why the engine is in FAILED state (from the engine), if known. */
    val reason: String = "",
    val iface: String = "",
    val upstream: String = "",
    /** Tailscale VPN interface the engine routes to (empty = not connected / not used). */
    val tailscale: String = "",
    /** md5 of the config files the running engine uses (empty when not running). */
    val configSum: String = "",
    val hostapdAlive: Boolean = false,
    val dhcpAlive: Boolean = false,
    val daemonAlive: Boolean = false,
    val clients: List<Client> = emptyList(),
    val log: List<String> = emptyList(),
    /** Version of SegAP's own Tailscale binaries on the phone (empty = not installed). */
    val tailscaleVersion: String = "",
    /** Per group using an exit node: whether its traffic currently goes through it. */
    val exits: List<ExitStatus> = emptyList(),
    /** Per group shared with the tailnet: whether its subnet route is approved. */
    val subnets: List<SubnetStatus> = emptyList(),
    /** Frequency the hotspot runs on (MHz; Shizuku engine only, 0 = unknown). */
    val frequencyMhz: Int = 0,
    /** What the phone's own hotspot supports (Shizuku engine only; null with root). */
    val phoneHotspot: PhoneHotspotSupport? = null,
    /** [ShizukuConfigPrint] of the phone hotspot's live settings (Shizuku engine only). */
    val liveConfigPrint: String = "",
) {
    fun exit(groupId: String): ExitStatus? = exits.firstOrNull { it.groupId == groupId }
    fun subnet(groupId: String): SubnetStatus? = subnets.firstOrNull { it.groupId == groupId }

    companion object {
        fun parse(output: String): EngineStatus {
            var s = EngineStatus()
            val clients = mutableListOf<Client>()
            val log = mutableListOf<String>()
            val exits = mutableListOf<ExitStatus>()
            val subnets = mutableListOf<SubnetStatus>()
            var inLog = false
            for (raw in output.lineSequence()) {
                val line = raw.trimEnd()
                when {
                    line == "LOG_BEGIN" -> inLog = true
                    line == "LOG_END" -> inLog = false
                    inLog -> if (line.isNotBlank()) log += line
                    line.startsWith("CLIENT ") -> {
                        // CLIENT <mac> <group> <ip|-> <hostname|-> <since-epoch> <upload-bytes>
                        //        [<download-bytes|-> <rssi-dbm|-> <link-down-kbps|-> <link-up-kbps|->]
                        val p = line.split(" ")
                        fun opt(i: Int) = p.getOrNull(i)?.takeIf { it != "-" }
                        if (p.size >= 7) clients += Client(
                            mac = p[1], groupId = p[2],
                            ip = p[3].takeIf { it != "-" }, hostname = p[4].takeIf { it != "-" && it != "*" },
                            connectedSinceEpoch = p[5].toLongOrNull() ?: 0, uploadBytes = p[6].toLongOrNull() ?: 0,
                            downloadBytes = opt(7)?.toLongOrNull(),
                            rssi = opt(8)?.toIntOrNull()?.takeIf { it in -120..0 },
                            linkDownKbps = opt(9)?.toLongOrNull(),
                            linkUpKbps = opt(10)?.toLongOrNull(),
                        )
                    }
                    line.startsWith("EXIT ") -> {
                        // EXIT <group> <active|fallback> <tailscale backend state|-> <exit node ip>
                        val p = line.split(" ")
                        if (p.size >= 5) exits += ExitStatus(
                            groupId = p[1], active = p[2] == "active",
                            backendState = p[3].takeIf { it != "-" } ?: "", nodeIp = p[4],
                        )
                    }
                    line.startsWith("SUBNET ") -> {
                        // SUBNET <group> <subnet> <approved 0|1> <tailscale backend state|->
                        val p = line.split(" ")
                        if (p.size >= 5) subnets += SubnetStatus(
                            groupId = p[1], subnet = p[2], approved = p[3] == "1",
                            backendState = p[4].takeIf { it != "-" } ?: "",
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
                            "REASON" -> s.copy(reason = v)
                            "IFACE" -> s.copy(iface = v)
                            "UPSTREAM" -> s.copy(upstream = v)
                            "TAILSCALE" -> s.copy(tailscale = v)
                            "CFGSUM" -> s.copy(configSum = v)
                            "HOSTAPD" -> s.copy(hostapdAlive = v == "1")
                            "DHCP" -> s.copy(dhcpAlive = v == "1")
                            "DAEMON" -> s.copy(daemonAlive = v == "1")
                            "TSBIN" -> s.copy(tailscaleVersion = v)
                            "FREQ" -> s.copy(frequencyMhz = v.toIntOrNull() ?: 0)
                            "SHZ_CONF" -> s.copy(liveConfigPrint = v)
                            "SHZ_MAXCLIENTS" -> s.copy(phoneHotspot = (s.phoneHotspot ?: PhoneHotspotSupport()).copy(maxClients = v == "1"))
                            "SHZ_BLOCK" -> s.copy(phoneHotspot = (s.phoneHotspot ?: PhoneHotspotSupport()).copy(blocking = v == "1"))
                            "SHZ_ISOLATION" -> s.copy(phoneHotspot = (s.phoneHotspot ?: PhoneHotspotSupport()).copy(isolation = v == "1"))
                            "SHZ_SAMSUNG" -> s.copy(phoneHotspot = (s.phoneHotspot ?: PhoneHotspotSupport()).copy(samsung = v == "1"))
                            "SHZ_5GHZ" -> s.copy(phoneHotspot = (s.phoneHotspot ?: PhoneHotspotSupport())
                                .copy(channels5 = v.split(',').mapNotNull { it.trim().toIntOrNull() }))
                            else -> s
                        }
                    }
                }
            }
            return s.copy(clients = clients, log = log, exits = exits, subnets = subnets)
        }
    }
}

/** What the phone's own hotspot can do (reported by the Shizuku engine). */
/**
 * Fingerprint of the phone hotspot's name, password and hidden flag: the Shizuku service reports it for the live
 * settings, the app computes it for the config it applied. Different = the hotspot no longer runs SegAP's settings.
 */
object ShizukuConfigPrint {
    fun of(ssid: String?, passphrase: String?, hidden: Boolean): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("${ssid ?: ""}\u0000${passphrase ?: ""}\u0000$hidden".toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    fun of(c: HotspotConfig): String = of(c.ssid, c.groups.firstOrNull()?.password, c.hidden)
}

data class PhoneHotspotSupport(
    val maxClients: Boolean = false,
    /** Android's block list (needs the driver's "client force disconnect"). */
    val blocking: Boolean = false,
    /** Devices on the hotspot can't reach each other. */
    val isolation: Boolean = false,
    val samsung: Boolean = false,
    /** 5 GHz channels allowed for a hotspot here (empty = none or unknown). */
    val channels5: List<Int> = emptyList(),
)

/** Engine's view of one group shared with the tailnet (as of its watchdog's last pass). */
data class SubnetStatus(
    val groupId: String,
    val subnet: String,
    /** The tailnet approved the route and this instance serves it. */
    val approved: Boolean,
    val backendState: String,
)

/** Engine's view of one exit-node group (as of its watchdog's last pass, every few seconds). */
data class ExitStatus(
    val groupId: String,
    /** The group's internet goes through the exit node right now (else: normal internet). */
    val active: Boolean,
    /** Tailscale state of the group's instance: Running, NeedsLogin, Starting, down... */
    val backendState: String,
    val nodeIp: String,
)

/** A device in the tailnet that offers itself as exit node. */
data class ExitNodeOption(val name: String, val ip: String, val online: Boolean, val os: String)

/** State of one group's Tailscale instance, from "mh_engine.sh ts-status". */
data class TailscaleInstance(
    /** Tailscale's BackendState (Running, NeedsLogin, Starting, Stopped...), "down" = not running, "" = unknown. */
    val backendState: String = "",
    /** Login URL while [backendState] is NeedsLogin. */
    val authUrl: String = "",
    val selfName: String = "",
    val selfIp: String = "",
    val exitNodes: List<ExitNodeOption> = emptyList(),
    /** Exit node the instance currently uses (from its own status) and whether it's reachable. */
    val currentExitIp: String = "",
    val currentExitOnline: Boolean = false,
    /** Subnet routes the tailnet approved for this instance (and it serves). */
    val approvedRoutes: List<String> = emptyList(),
    val error: String = "",
) {
    val running: Boolean get() = backendState == "Running"
    val needsLogin: Boolean get() = backendState == "NeedsLogin"
    /** Signed in, but the tailnet requires an admin to approve new devices. */
    val needsApproval: Boolean get() = backendState == "NeedsMachineAuth"

    companion object {
        fun parse(output: String): TailscaleInstance {
            var t = TailscaleInstance()
            val json = StringBuilder()
            var inJson = false
            for (raw in output.lineSequence()) {
                val line = raw.trimEnd()
                when {
                    line == "JSON_BEGIN" -> inJson = true
                    line == "JSON_END" -> inJson = false
                    inJson -> json.appendLine(line)
                    line.startsWith("TSSTATE=") -> t = t.copy(backendState = line.substringAfter('='))
                    line.startsWith("TSAUTH=") -> t = t.copy(authUrl = line.substringAfter('='))
                    line.startsWith("TSERR=") -> t = t.copy(error = line.substringAfter('='))
                    line.startsWith("ERROR") -> t = t.copy(error = line.substringAfter(':').trim())
                }
            }
            if (json.isBlank()) return t
            return runCatching { fromStatusJson(org.json.JSONObject(json.toString()), t) }.getOrDefault(t)
        }

        /** Reads what the app shows from "tailscale status --json". */
        private fun fromStatusJson(o: org.json.JSONObject, base: TailscaleInstance): TailscaleInstance {
            fun ipv4(node: org.json.JSONObject?): String {
                val ips = node?.optJSONArray("TailscaleIPs") ?: return ""
                return (0 until ips.length()).map { ips.optString(it).substringBefore('/') }.firstOrNull { '.' in it } ?: ""
            }
            fun displayName(node: org.json.JSONObject): String =
                node.optString("DNSName").substringBefore('.').ifBlank { node.optString("HostName") }
            val self = o.optJSONObject("Self")
            val nodes = mutableListOf<ExitNodeOption>()
            o.optJSONObject("Peer")?.let { peers ->
                for (key in peers.keys()) {
                    val p = peers.optJSONObject(key) ?: continue
                    if (!p.optBoolean("ExitNodeOption")) continue
                    val ip = ipv4(p)
                    if (ip.isBlank()) continue
                    nodes += ExitNodeOption(displayName(p), ip, p.optBoolean("Online"), p.optString("OS"))
                }
            }
            val exit = o.optJSONObject("ExitNodeStatus")
            return base.copy(
                backendState = o.optString("BackendState").ifBlank { base.backendState },
                authUrl = o.optString("AuthURL").ifBlank { base.authUrl },
                selfName = self?.let { displayName(it) } ?: "",
                selfIp = ipv4(self),
                exitNodes = nodes.sortedWith(compareBy({ !it.online }, { it.name.lowercase() })),
                currentExitIp = ipv4(exit),
                currentExitOnline = exit?.optBoolean("Online") ?: false,
                approvedRoutes = self?.optJSONArray("PrimaryRoutes")?.let { r -> (0 until r.length()).map { r.optString(it) } } ?: emptyList(),
            )
        }
    }
}
