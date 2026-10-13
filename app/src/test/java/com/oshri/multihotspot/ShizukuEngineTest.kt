package com.oshri.multihotspot

import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.engine.Capabilities
import com.oshri.multihotspot.model.EngineState
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.PhoneHotspotSupport
import com.oshri.multihotspot.model.ShizukuConfigPrint
import com.oshri.multihotspot.model.StaticLease
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.normalized
import com.oshri.multihotspot.model.shizukuView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shizuku engine: its status format, what it can do, and which settings changes it needs applied. */
class ShizukuEngineTest {

    private fun group(id: String, gw: String, password: String = "password-$id") = Group(
        id = id, name = id, password = password, gateway = gw, prefix = 24,
        dhcpStart = gw.substringBeforeLast('.') + ".100", dhcpEnd = gw.substringBeforeLast('.') + ".200",
    )

    private val config = HotspotConfig(
        ssid = "Test", channel = 6, bssid = "02:00:00:00:00:01",
        groups = listOf(group("g1", "192.168.10.1"), group("g2", "192.168.20.1")),
    )

    // What HotspotCore.status() prints for a running Samsung hotspot with one device.
    private val running = """
        STATE=running
        IFACE=swlan0
        UPSTREAM=rmnet_data1
        FREQ=5745
        SHZ_MAXCLIENTS=1
        SHZ_BLOCK=0
        SHZ_ISOLATION=1
        SHZ_SAMSUNG=1
        SHZ_5GHZ=149,153,157,161,165
        CLIENT aa:bb:cc:dd:ee:ff g1 192.168.43.17 Galaxy-Tab 1760000000 0 - - - -
        CLIENT 11:22:33:44:55:66 g1 - - 1760000100 0 - - - -
        LOG_BEGIN
        10:04:01 hotspot on
        LOG_END
    """.trimIndent()

    @Test fun parsesShizukuStatus() {
        val s = EngineStatus.parse(running)
        assertEquals(EngineState.RUNNING, s.state)
        assertEquals("rmnet_data1", s.upstream)
        assertEquals(5745, s.frequencyMhz)
        assertEquals(PhoneHotspotSupport(maxClients = true, blocking = false, isolation = true, samsung = true,
            channels5 = listOf(149, 153, 157, 161, 165)), s.phoneHotspot)
        assertEquals(2, s.clients.size)
        val tab = s.clients[0]
        assertEquals("192.168.43.17", tab.ip)
        assertEquals("Galaxy-Tab", tab.hostname)
        assertEquals(1760000000L, tab.connectedSinceEpoch)
        // No driver stats from the phone's own hotspot.
        assertNull(tab.rssi)
        assertNull(tab.downloadBytes)
        assertNull(s.clients[1].ip)
        assertNull(s.clients[1].hostname)
        assertEquals(listOf("10:04:01 hotspot on"), s.log)
        // The root engine's status has no phone-hotspot part.
        assertNull(EngineStatus.parse("STATE=running\nLOG_BEGIN\nLOG_END").phoneHotspot)
    }

    @Test fun capabilities() {
        val root = Capabilities.ROOT
        assertTrue(root.multipleNetworks && root.fixedIps && root.tailscale && root.firewall && root.deviceStats)
        assertFalse(root.band5GHz)

        val s10 = Capabilities.shizuku(PhoneHotspotSupport(maxClients = true, blocking = false, isolation = true, channels5 = listOf(149)))
        assertFalse(s10.multipleNetworks || s10.addressing || s10.firewall || s10.fixedIps || s10.tailscale || s10.deviceStats || s10.radioDetails)
        assertTrue(s10.maxClients && s10.deviceIsolation && s10.band5GHz)
        assertFalse(s10.blocking)

        // Until the service reports what the phone supports, nothing phone-dependent is offered.
        val unknown = Capabilities.shizuku(null)
        assertFalse(unknown.maxClients || unknown.blocking || unknown.deviceIsolation || unknown.band5GHz)
    }

    @Test fun shizukuViewOnlyCountsWhatThePhoneHotspotUses() {
        val base = config.shizukuView()
        // Root-only settings: no Apply needed with Shizuku.
        assertEquals(base, config.copy(groups = listOf(config.groups[0], group("g2", "192.168.30.1", "other-pass"))).shizukuView())
        assertEquals(base, config.copy(groups = listOf(config.groups[0].copy(gateway = "192.168.11.1", internet = false))).shizukuView())
        assertEquals(base, config.copy(staticLeases = listOf(StaticLease("aa:bb:cc:dd:ee:ff", "g1", "192.168.10.50", ""))).shizukuView())
        assertEquals(base, config.copy(strictIsolation = false, bssid = "02:00:00:00:00:02", iface = "wlan0").shizukuView())
        // On 5 GHz the 2.4 GHz channel doesn't matter.
        val on5 = config.copy(band5 = true, channel5 = 149)
        assertEquals(on5.shizukuView(), on5.copy(channel = 11).shizukuView())
        // Used by the phone's hotspot.
        assertNotEquals(base, config.copy(groups = listOf(config.groups[0].copy(password = "changed-pass"))).shizukuView())
        assertNotEquals(base, config.copy(groups = listOf(config.groups[0].copy(devicesSeeEachOther = false))).shizukuView())
        assertNotEquals(base, config.copy(ssid = "Other").shizukuView())
        assertNotEquals(base, config.copy(channel = 11).shizukuView())
        assertNotEquals(base, on5.shizukuView())
        assertNotEquals(base, config.copy(maxClients = 3).shizukuView())
        assertNotEquals(base, config.copy(hidden = true).shizukuView())
        assertNotEquals(base, config.copy(blockedMacs = listOf("aa:bb:cc:dd:ee:ff")).shizukuView())
        // Block list order and case don't matter.
        assertEquals(
            config.copy(blockedMacs = listOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66")).shizukuView(),
            config.copy(blockedMacs = listOf("11:22:33:44:55:66", "aa:bb:cc:dd:ee:ff")).shizukuView(),
        )
    }

    @Test fun liveConfigFingerprint() {
        val print = ShizukuConfigPrint.of(config)
        assertEquals(print, ShizukuConfigPrint.of("Test", "password-g1", false))
        assertEquals(16, print.length)
        // Changed in Android's settings: name, password or hidden flag.
        assertNotEquals(print, ShizukuConfigPrint.of("Test2", "password-g1", false))
        assertNotEquals(print, ShizukuConfigPrint.of("Test", "password-g9", false))
        assertNotEquals(print, ShizukuConfigPrint.of("Test", "password-g1", true))
        // Only the first network's password counts; the rest of the config doesn't.
        assertEquals(print, ShizukuConfigPrint.of(config.copy(channel = 11, groups = config.groups.take(1))))
        assertEquals(print, EngineStatus.parse("STATE=running\nSHZ_CONF=$print\nLOG_BEGIN\nLOG_END").liveConfigPrint)
    }

    @Test fun rootIgnores5GHz() {
        assertEquals(config.normalized(), config.copy(band5 = true, channel5 = 36).normalized())
    }

    @Test fun shizukuValidatesOnlyTheFirstNetwork() {
        // The second network clashes with the first: root can't start, Shizuku (first network only) can.
        val clash = config.copy(groups = listOf(config.groups[0], group("g2", "192.168.10.2")))
        assertTrue(Validation.config(clash).isNotEmpty())
        assertTrue(Validation.shizukuConfig(clash).isEmpty())
        // Access to the other networks doesn't apply either (it isn't "a network that no longer exists").
        val reach = config.copy(groups = listOf(config.groups[0].copy(canReach = listOf("g2")), config.groups[1]))
        assertTrue(Validation.config(reach).isEmpty())
        assertTrue(Validation.shizukuConfig(reach).isEmpty())
        // ...but the first network's password still has to be valid.
        val badPw = config.copy(groups = listOf(config.groups[0].copy(password = "short")))
        assertTrue(Validation.shizukuConfig(badPw).isNotEmpty())
        assertTrue(Validation.config(config.copy(channel5 = 52)).any { "5 GHz" in it })
    }

    @Test fun jsonKeeps5GHz() {
        val c = config.copy(band5 = true, channel5 = 149)
        assertEquals(c, ConfigStore.fromJson(ConfigStore.toJson(c)))
        // Configs from before 5 GHz: 2.4 GHz.
        val old = ConfigStore.toJson(c).apply { remove("band5"); remove("channel5") }
        assertFalse(ConfigStore.fromJson(old).band5)
    }
}
