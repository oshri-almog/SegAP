package com.oshri.multihotspot

import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.engine.ConfigWriter
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.Ipv4
import com.oshri.multihotspot.model.Mac
import com.oshri.multihotspot.model.StaticLease
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.WifiMode
import com.oshri.multihotspot.model.normalized
import com.oshri.multihotspot.model.suggestNewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    private fun group(id: String, gw: String, pw: String = "password-$id", prefix: Int = 24) = Group(
        id = id, name = id.uppercase(), password = pw, gateway = gw, prefix = prefix,
        dhcpStart = gw.substringBeforeLast('.') + ".100", dhcpEnd = gw.substringBeforeLast('.') + ".200",
    )

    private fun config(vararg groups: Group) = HotspotConfig(ssid = "Test", channel = 6, bssid = "02:00:00:00:00:01", groups = groups.toList())

    @Test fun ipv4Parse() {
        assertEquals(0xC0A80A01L, Ipv4.parse("192.168.10.1"))
        assertNull(Ipv4.parse("192.168.10"))
        assertNull(Ipv4.parse("192.168.10.256"))
        assertNull(Ipv4.parse("192.168.010.1"))
        assertNull(Ipv4.parse("a.b.c.d"))
        assertEquals("10.0.0.255", Ipv4.format(Ipv4.parse("10.0.0.255")!!))
    }

    @Test fun ipv4Math() {
        val ip = Ipv4.parse("192.168.10.77")!!
        assertEquals("192.168.10.0", Ipv4.format(Ipv4.network(ip, 24)))
        assertEquals("192.168.10.255", Ipv4.format(Ipv4.broadcast(ip, 24)))
        assertEquals("255.255.255.252", Ipv4.format(Ipv4.mask(30)))
        assertEquals("255.255.0.0", Ipv4.format(Ipv4.mask(16)))
        assertTrue(Ipv4.overlaps(Ipv4.parse("10.0.0.1")!!, 16, Ipv4.parse("10.0.5.1")!!, 24))
        assertFalse(Ipv4.overlaps(Ipv4.parse("192.168.10.1")!!, 24, Ipv4.parse("192.168.20.1")!!, 24))
        assertTrue(Ipv4.isPrivate(Ipv4.parse("172.20.1.1")!!))
        assertFalse(Ipv4.isPrivate(Ipv4.parse("172.32.1.1")!!))
    }

    @Test fun macNormalizeAndRandom() {
        assertEquals("aa:bb:cc:dd:ee:ff", Mac.normalize("AA-BB-CC-DD-EE-FF"))
        assertNull(Mac.normalize("aa:bb:cc:dd:ee"))
        repeat(50) {
            val m = Mac.randomLocal()
            assertNotNull(Mac.normalize(m))
            val first = m.substring(0, 2).toInt(16)
            assertEquals(2, first and 0x03) // locally administered, unicast
        }
    }

    @Test fun defaultConfigIsValid() {
        assertEquals(emptyList<String>(), Validation.config(HotspotConfig.default()))
    }

    @Test fun validationCatchesProblems() {
        val a = group("g1", "192.168.10.1")
        val b = group("g2", "192.168.10.1", pw = a.password) // same subnet AND same password
        val errors = Validation.config(config(a, b))
        assertTrue(errors.any { it.contains("overlapping") })
        assertTrue(errors.any { it.contains("same password") })

        assertTrue(Validation.group(a.copy(gateway = "192.168.10.150")).any { it.contains("outside the DHCP range") })
        assertTrue(Validation.group(a.copy(dhcpEnd = "192.168.11.5")).any { it.contains("inside") })
        assertTrue(Validation.group(a.copy(gateway = "8.8.8.1")).any { it.contains("private") })
        assertTrue(Validation.group(a.copy(password = "short")).isNotEmpty())
        assertTrue(Validation.group(a.copy(password = " leading space")).isNotEmpty())
        assertTrue(Validation.group(a.copy(dhcpStart = "192.168.10.0")).isNotEmpty())
        assertTrue(Validation.ssid("x".repeat(33)) != null)
        assertTrue(Validation.ssid("שלום".repeat(4)) == null) // 32 bytes UTF-8
        assertTrue(Validation.ssid("שלום".repeat(5)) != null)
    }

    @Test fun validationIfaceAndLeases() {
        val g = group("g1", "192.168.10.1")
        assertTrue(Validation.config(config(g).copy(iface = "wlan0")).any { it.contains("wlan0") })
        assertTrue(Validation.config(config(g).copy(iface = "wlan0", wifiMode = WifiMode.OFF)).isEmpty())
        val bad = config(g).copy(staticLeases = listOf(StaticLease("aa:bb:cc:dd:ee:ff", "g1", "192.168.20.5")))
        assertTrue(Validation.config(bad).any { it.contains("outside") })
        val gw = config(g).copy(staticLeases = listOf(StaticLease("aa:bb:cc:dd:ee:ff", "g1", "192.168.10.1")))
        assertTrue(Validation.config(gw).any { it.contains("gateway") })
    }

    @Test fun suggestNewGroupIsFreeAndValid() {
        val c = HotspotConfig.default()
        val n = c.suggestNewGroup("New")
        val next = c.copy(groups = c.groups + n)
        assertEquals(emptyList<String>(), Validation.config(next))
        assertEquals("g3", n.id)
    }

    @Test fun configWriterFiles() {
        val c = config(group("g1", "192.168.10.1"), group("g2", "192.168.20.1").copy(internet = false, devicesSeeEachOther = false))
            .copy(ssid = "My Net ✓", hidden = true, blockedMacs = listOf("AA:BB:CC:DD:EE:FF"))
        val hostapd = ConfigWriter.hostapdConf(c, "IL")
        assertTrue(hostapd.contains("ssid2=" + "My Net ✓".toByteArray().joinToString("") { "%02x".format(it) }))
        assertTrue(hostapd.contains("ignore_broadcast_ssid=1"))
        assertTrue(hostapd.contains("channel=6"))
        assertTrue(hostapd.contains("country_code=IL"))
        assertTrue(hostapd.contains("deny_mac_file="))

        val groups = ConfigWriter.groupsConf(c).trim().lines()
        assertEquals("g1 192.168.10.1 24 192.168.10.0/24 255.255.255.0 1 0 192.168.10.100 192.168.10.200 12h 1.1.1.1,8.8.8.8 0 -", groups[0])
        assertEquals("g2 192.168.20.1 24 192.168.20.0/24 255.255.255.0 0 1 192.168.20.100 192.168.20.200 12h 1.1.1.1,8.8.8.8 0 -", groups[1])
        val ts = c.copy(groups = c.groups.map { if (it.id == "g2") it.copy(tailscale = true) else it })
        assertTrue(ConfigWriter.groupsConf(ts).trim().lines()[1].endsWith(" 1 -"))
        // engine reads 13 whitespace-separated columns: no field may contain spaces
        ConfigWriter.groupsConf(ts).trim().lines().forEach { assertEquals(13, it.split(" ").size) }
        // access to other networks: known ids only, never itself
        val reach = c.copy(groups = c.groups.map { if (it.id == "g1") it.copy(canReach = listOf("g2", "g1", "gone")) else it })
        assertTrue(ConfigWriter.groupsConf(reach).trim().lines()[0].endsWith(" 0 g2"))
        assertEquals("keyid=g1 00:00:00:00:00:00 password-g1", ConfigWriter.pskFile(c).lines()[0])
        assertEquals("aa:bb:cc:dd:ee:ff\n", ConfigWriter.denyFile(c))
        assertEquals("g2", ConfigWriter.defaultGroupId(c)) // most restricted
    }

    @Test fun statusParse() {
        val out = """
            STATE=running
            IFACE=swlan0
            UPSTREAM=wlan0
            HOSTAPD=1
            DHCP=1
            DAEMON=0
            CLIENT 02:00:00:00:00:01 g2 192.168.20.169 Phone-A 1791823287 3300000
            CLIENT 02:00:00:00:00:02 g1 - - 1791823133 0
            LOG_BEGIN
            10-12 19:28:35 start iface=swlan0 wifi_mode=keep
            LOG_END
        """.trimIndent()
        val s = EngineStatus.parse(out)
        assertEquals(EngineState.RUNNING, s.state)
        assertEquals("wlan0", s.upstream)
        assertTrue(s.hostapdAlive && s.dhcpAlive && !s.daemonAlive)
        assertEquals(2, s.clients.size)
        assertEquals("192.168.20.169", s.clients[0].ip)
        assertEquals(3_300_000L, s.clients[0].uploadBytes)
        assertNull(s.clients[1].ip)
        assertNull(s.clients[1].hostname)
        assertEquals(1, s.log.size)
    }

    @Test fun wifiQrPayloadEscapes() {
        assertEquals("WIFI:T:WPA;S:Home;P:abc12345;;", com.oshri.multihotspot.ui.wifiQrPayload("Home", "abc12345", false))
        assertEquals(
            "WIFI:T:WPA;S:a\\;b\\,c\\:d;P:p\\\\w\\\"x;H:true;;",
            com.oshri.multihotspot.ui.wifiQrPayload("a;b,c:d", "p\\w\"x", true),
        )
    }

    @Test fun normalizedIgnoresOrder() {
        val a = HotspotConfig.default().copy(
            staticLeases = listOf(StaticLease("aa:aa:aa:aa:aa:aa", "g1", "192.168.10.5"), StaticLease("bb:bb:bb:bb:bb:bb", "g2", "192.168.20.5")),
            blockedMacs = listOf("CC:CC:CC:CC:CC:CC", "11:11:11:11:11:11"),
        )
        val b = a.copy(staticLeases = a.staticLeases.reversed(), blockedMacs = listOf("11:11:11:11:11:11", "cc:cc:cc:cc:cc:cc"))
        assertTrue(a != b)
        assertEquals(a.normalized(), b.normalized())
    }

    @Test fun statusParsesReasonAndTailscale() {
        val s = EngineStatus.parse("STATE=failed\nREASON=The radio was taken over\nTAILSCALE=tun0\nHOSTAPD=0")
        assertEquals(EngineState.FAILED, s.state)
        assertEquals("The radio was taken over", s.reason)
        assertEquals("tun0", s.tailscale)
    }

    @Test fun statusParsesRadioStats() {
        val s = EngineStatus.parse(
            listOf(
                "STATE=running",
                "CLIENT 02:00:00:00:00:01 g2 192.168.20.169 S24 1791823287 775841 600198 -21 192500 6000",
                "CLIENT 02:00:00:00:00:02 g1 - - 1791823133 1200 - - - -",
            ).joinToString("\n")
        )
        val a = s.clients[0]
        assertEquals(775841L, a.uploadBytes)
        assertEquals(600198L, a.downloadBytes)
        assertEquals(-21, a.rssi)
        assertEquals(192500L, a.linkDownKbps)
        assertEquals(6000L, a.linkUpKbps)
        assertEquals(775841L + 600198L, a.totalBytes)
        val b = s.clients[1]
        assertNull(b.downloadBytes)
        assertNull(b.rssi)
        assertEquals(1200L, b.totalBytes)
    }

    @Test fun validationRejectsUnknownReach() {
        val c = HotspotConfig.default()
        val bad = c.copy(groups = c.groups.map { if (it.id == "g1") it.copy(canReach = listOf("g9")) else it })
        assertTrue(Validation.config(bad).any { it.contains("no longer exists") })
        val ok = c.copy(groups = c.groups.map { if (it.id == "g1") it.copy(canReach = listOf("g2")) else it })
        assertEquals(emptyList<String>(), Validation.config(ok))
    }

    @Test fun configStoreRoundTrip() {
        val c = HotspotConfig.default().copy(
            ssid = "Round ✓", channel = 11, hidden = true, wifiMode = WifiMode.OFF, strictIsolation = false,
            groups = HotspotConfig.default().groups.map { it.copy(tailscale = it.id == "g1", canReach = if (it.id == "g1") listOf("g2") else emptyList()) },
            staticLeases = listOf(StaticLease("aa:bb:cc:dd:ee:ff", "g1", "192.168.10.50", "TV")),
            blockedMacs = listOf("11:22:33:44:55:66"),
        )
        val back = ConfigStore.fromJson(ConfigStore.toJson(c))
        assertEquals(c, back)
    }
}
