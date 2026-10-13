package com.oshri.multihotspot.model

/** Minimal IPv4 helpers (addresses as unsigned 32-bit values in a Long). */
object Ipv4 {

    fun parse(text: String): Long? {
        val parts = text.trim().split(".")
        if (parts.size != 4) return null
        var value = 0L
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || p.any { !it.isDigit() }) return null
            if (p.length > 1 && p[0] == '0') return null
            val n = p.toInt()
            if (n > 255) return null
            value = (value shl 8) or n.toLong()
        }
        return value
    }

    fun format(value: Long): String =
        "${(value shr 24) and 255}.${(value shr 16) and 255}.${(value shr 8) and 255}.${value and 255}"

    fun isValid(text: String): Boolean = parse(text) != null

    fun mask(prefix: Int): Long = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    fun network(ip: Long, prefix: Int): Long = ip and mask(prefix)

    fun broadcast(ip: Long, prefix: Int): Long = network(ip, prefix) or (mask(prefix).inv() and 0xFFFFFFFFL)

    fun inSubnet(ip: Long, net: Long, prefix: Int): Boolean = network(ip, prefix) == network(net, prefix)

    /** True for RFC 1918 private ranges. */
    fun isPrivate(ip: Long): Boolean {
        val a = (ip shr 24) and 255
        val b = (ip shr 16) and 255
        return a == 10L || (a == 172L && b in 16..31) || (a == 192L && b == 168L)
    }

    /**
     * Reachable through a Tailscale exit node: not private, CGNAT/tailnet (100.64/10), loopback, link-local,
     * benchmark (198.18/15), multicast/reserved or 0/8 - the ranges SegAP's instances refuse to forward.
     */
    fun isPublic(ip: Long): Boolean {
        val blocked = listOf("0.0.0.0" to 8, "10.0.0.0" to 8, "100.64.0.0" to 10, "127.0.0.0" to 8, "169.254.0.0" to 16,
            "172.16.0.0" to 12, "192.168.0.0" to 16, "198.18.0.0" to 15, "224.0.0.0" to 3)
        return blocked.none { (net, p) -> inSubnet(ip, parse(net)!!, p) }
    }

    fun overlaps(a: Long, aPrefix: Int, b: Long, bPrefix: Int): Boolean {
        val p = minOf(aPrefix, bPrefix)
        return network(a, p) == network(b, p)
    }
}

object Mac {
    private val regex = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")

    fun normalize(text: String): String? {
        val t = text.trim().lowercase().replace('-', ':')
        return if (regex.matches(t)) t else null
    }

    fun randomLocal(random: java.util.Random = java.security.SecureRandom()): String {
        val b = ByteArray(6).also { random.nextBytes(it) }
        b[0] = ((b[0].toInt() and 0xFC) or 0x02).toByte() // locally administered, unicast
        return b.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
    }
}
