package com.oshri.multihotspot.model

object Validation {

    const val MAX_GROUPS = 8
    const val MAX_CLIENTS = 10 // Samsung's driver reports maxClient=10

    fun ssid(ssid: String): String? = when {
        ssid.isEmpty() -> "Network name is empty"
        ssid.toByteArray(Charsets.UTF_8).size > 32 -> "Network name is longer than 32 bytes"
        else -> null
    }

    fun password(pw: String): String? = when {
        pw.length < 8 -> "Password must be at least 8 characters"
        pw.length > 63 -> "Password must be at most 63 characters"
        pw.any { it.code < 32 || it.code > 126 } -> "Use plain ASCII characters only"
        pw.first() == ' ' || pw.last() == ' ' -> "Password can't start or end with a space"
        else -> null
    }

    /** Returns field-level problems for one group (empty list = valid). */
    fun group(g: Group): List<String> {
        val errors = mutableListOf<String>()
        if (g.name.isBlank()) errors += "Name is empty"
        password(g.password)?.let { errors += it }
        if (g.prefix !in 16..30) errors += "Prefix must be between /16 and /30"
        val gw = Ipv4.parse(g.gateway)
        if (gw == null) {
            errors += "Gateway is not a valid IPv4 address"
            return errors
        }
        if (!Ipv4.isPrivate(gw)) errors += "Gateway must be a private address (10.x, 172.16-31.x, 192.168.x)"
        val net = Ipv4.network(gw, g.prefix)
        val bc = Ipv4.broadcast(gw, g.prefix)
        if (gw == net || gw == bc) errors += "Gateway can't be the network or broadcast address"
        val ds = Ipv4.parse(g.dhcpStart)
        val de = Ipv4.parse(g.dhcpEnd)
        if (ds == null || de == null) {
            errors += "DHCP range is not valid"
        } else {
            if (!Ipv4.inSubnet(ds, gw, g.prefix) || !Ipv4.inSubnet(de, gw, g.prefix)) errors += "DHCP range must be inside ${g.networkCidr}"
            else if (ds > de) errors += "DHCP start must be before DHCP end"
            else if (ds == net || de == bc) errors += "DHCP range can't include the network or broadcast address"
            else if (gw in ds..de) errors += "Gateway must be outside the DHCP range"
        }
        if (g.leaseHours !in 1..168) errors += "Lease time must be 1-168 hours"
        if (!Ipv4.isValid(g.dns1)) errors += "DNS 1 is not a valid IPv4 address"
        if (g.dns2.isNotBlank() && !Ipv4.isValid(g.dns2)) errors += "DNS 2 is not a valid IPv4 address"
        return errors
    }

    /** Whole-config check. Returns human-readable problems (empty list = OK to start). */
    fun config(c: HotspotConfig): List<String> {
        val errors = mutableListOf<String>()
        ssid(c.ssid)?.let { errors += it }
        if (c.channel !in HotspotConfig.CHANNELS_24GHZ) errors += "Channel must be 1-13"
        if (c.maxClients !in 1..MAX_CLIENTS) errors += "Max devices must be 1-$MAX_CLIENTS"
        if (Mac.normalize(c.bssid) == null) errors += "Invalid BSSID"
        if (c.iface !in listOf("swlan0", "wlan0")) errors += "Unknown radio interface ${c.iface}"
        if (c.iface == "wlan0" && c.wifiMode != WifiMode.OFF) errors += "Using wlan0 requires turning the phone's Wi-Fi off"
        if (c.groups.isEmpty()) errors += "Add at least one network"
        if (c.groups.size > MAX_GROUPS) errors += "At most $MAX_GROUPS networks"

        c.groups.forEach { g -> group(g).forEach { errors += "${g.name}: $it" } }
        c.groups.forEach { g ->
            g.canReach.filter { id -> id == g.id || c.groups.none { it.id == id } }
                .forEach { errors += "${g.name}: allowed to reach a network that no longer exists" }
        }

        c.groups.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors += "Duplicate network id $it" }
        c.groups.filter { !it.id.matches(Regex("^[a-z0-9]{1,16}$")) }.forEach { errors += "${it.name}: bad internal id" }
        c.groups.groupBy { it.password }.filter { it.value.size > 1 }.values.forEach { dup ->
            errors += "${dup.joinToString(" & ") { it.name }} use the same password - each network needs its own"
        }
        for (i in c.groups.indices) for (j in i + 1 until c.groups.size) {
            val a = c.groups[i]; val b = c.groups[j]
            val ga = Ipv4.parse(a.gateway) ?: continue
            val gb = Ipv4.parse(b.gateway) ?: continue
            if (Ipv4.overlaps(ga, a.prefix, gb, b.prefix)) errors += "${a.name} and ${b.name} have overlapping subnets"
        }

        c.staticLeases.forEach { l ->
            val g = c.group(l.groupId)
            val ip = Ipv4.parse(l.ip)
            when {
                Mac.normalize(l.mac) == null -> errors += "Fixed IP: '${l.mac}' is not a MAC address"
                g == null -> errors += "Fixed IP for ${l.mac}: its network no longer exists"
                ip == null -> errors += "Fixed IP for ${l.mac}: '${l.ip}' is not valid"
                !Ipv4.inSubnet(ip, Ipv4.parse(g.gateway) ?: 0, g.prefix) -> errors += "Fixed IP ${l.ip} is outside ${g.name} (${g.networkCidr})"
                l.ip == g.gateway -> errors += "Fixed IP ${l.ip} is the gateway of ${g.name}"
            }
        }
        c.staticLeases.groupBy { it.ip }.filter { it.value.size > 1 }.keys.forEach { errors += "Fixed IP $it is used twice" }
        c.staticLeases.groupBy { Mac.normalize(it.mac) }.filter { it.key != null && it.value.size > 1 }.keys
            .forEach { errors += "Device $it has more than one fixed IP" }
        c.blockedMacs.filter { Mac.normalize(it) == null }.forEach { errors += "Blocked device '$it' is not a MAC address" }
        return errors
    }
}
