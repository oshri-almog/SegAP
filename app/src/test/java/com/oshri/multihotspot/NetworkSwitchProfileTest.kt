package com.oshri.multihotspot

import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.engine.ConfigWriter
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.StaticLease
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.WifiMode
import com.oshri.multihotspot.model.active
import com.oshri.multihotspot.model.asProfile
import com.oshri.multihotspot.model.normalized
import com.oshri.multihotspot.model.shizukuNetwork
import com.oshri.multihotspot.model.shizukuView
import com.oshri.multihotspot.model.syncedProfiles
import com.oshri.multihotspot.model.withSettingsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Networks switched on/off, and hotspot profiles. */
class NetworkSwitchProfileTest {

    private fun group(id: String, gw: String, on: Boolean = true, reach: List<String> = emptyList()) = Group(
        id = id, name = id.uppercase(), password = "password-$id", gateway = gw, prefix = 24,
        dhcpStart = gw.substringBeforeLast('.') + ".100", dhcpEnd = gw.substringBeforeLast('.') + ".200",
        enabled = on, canReach = reach, shareSubnet = id == "g2",
    )

    private val config = HotspotConfig(
        ssid = "Test", channel = 6, bssid = "02:00:00:00:00:01",
        groups = listOf(group("g1", "192.168.10.1", reach = listOf("g2", "g3")), group("g2", "192.168.20.1", on = false), group("g3", "192.168.30.1")),
        staticLeases = listOf(
            StaticLease("aa:bb:cc:dd:ee:01", "g1", "192.168.10.50"),
            StaticLease("aa:bb:cc:dd:ee:02", "g2", "192.168.20.50"),
        ),
    )

    @Test fun engineOnlyGetsSwitchedOnNetworks() {
        val a = config.active()
        assertEquals(listOf("g1", "g3"), a.groups.map { it.id })
        // Access to a switched-off network and its fixed IPs go too.
        assertEquals(listOf("g3"), a.group("g1")!!.canReach)
        assertEquals(listOf("g1"), a.staticLeases.map { it.groupId })
        assertFalse("keyid=g2" in ConfigWriter.pskFile(a))
        assertFalse(ConfigWriter.groupsConf(a).lines().any { it.startsWith("g2 ") })
        // Its Tailscale instance isn't in ts.conf: the engine stops it (ts_down_unused).
        assertEquals("", ConfigWriter.tailscaleConf(a))
        assertTrue(ConfigWriter.tailscaleConf(config).startsWith("g2 "))
    }

    @Test fun startValidation() {
        assertTrue(Validation.forStart(config, shizuku = false).isEmpty())
        val allOff = config.copy(groups = config.groups.map { it.copy(enabled = false) })
        assertTrue(Validation.forStart(allOff, shizuku = false).single().contains("switched off"))
        // A switched-off network that clashes with another doesn't block the start.
        val clash = config.copy(groups = config.groups.map { if (it.id == "g2") it.copy(gateway = "192.168.10.2") else it })
        assertTrue(Validation.forStart(clash, shizuku = false).isEmpty())
    }

    @Test fun shizukuRunsTheFirstSwitchedOnNetwork() {
        val firstOff = config.copy(groups = config.groups.map { if (it.id == "g1") it.copy(enabled = false) else it })
        assertEquals("g3", firstOff.shizukuNetwork()!!.id)
        assertEquals("password-g3", firstOff.shizukuView().groups.single().password)
        assertEquals("g1", config.shizukuNetwork()!!.id)
    }

    @Test fun editingASwitchedOffNetworkNeedsNoApply() {
        val edited = config.copy(groups = config.groups.map { if (it.id == "g2") it.copy(password = "changed-pass") else it })
        assertEquals(config.normalized(), edited.normalized())
        // Switching it on does.
        val on = config.copy(groups = config.groups.map { it.copy(enabled = true) })
        assertNotEquals(config.normalized(), on.normalized())
    }

    @Test fun profiles() {
        // A config from before profiles gets "Default" with its settings.
        val c = config.syncedProfiles()
        assertEquals(listOf("Default"), c.profiles.map { it.name })
        assertEquals(c.profiles.single().id, c.activeProfile)
        assertEquals("Test", c.profiles.single().ssid)

        // Saving new settings keeps them in the active profile.
        val travel = c.asProfile("p2", "Travel").copy(ssid = "Road", channel = 11, hidden = true, wifiMode = WifiMode.OFF)
        val two = c.copy(profiles = c.profiles + travel)
        val edited = two.copy(channel = 1).syncedProfiles()
        assertEquals(1, edited.profiles.first { it.id == "p1" }.channel)
        assertEquals(11, edited.profiles.first { it.id == "p2" }.channel)

        // Switching applies the profile's settings; networks stay.
        val switched = edited.withSettingsOf(travel).syncedProfiles()
        assertEquals("p2", switched.activeProfile)
        assertEquals("Road", switched.ssid)
        assertEquals(11, switched.channel)
        assertTrue(switched.hidden)
        assertEquals(edited.groups, switched.groups)
        assertEquals(1, switched.profiles.first { it.id == "p1" }.channel)

        // Profile names and the profile list don't matter to the engine.
        val renamed = switched.copy(profiles = switched.profiles.map { it.copy(name = it.name + "!") })
        assertEquals(switched.normalized(), renamed.normalized())

        assertTrue(Validation.profileName("travel ", listOf("Travel")) != null)
        assertTrue(Validation.profileName("  ", emptyList()) != null)
        assertEquals(null, Validation.profileName("Home", listOf("Travel")))
    }

    @Test fun jsonRoundTrip() {
        val c = config.syncedProfiles().let { it.copy(profiles = it.profiles + it.asProfile("p2", "Travel").copy(band5 = true, channel5 = 149)) }
        assertEquals(c, ConfigStore.fromJson(ConfigStore.toJson(c)))
        // Old configs: networks on, no profiles (the app adds "Default").
        val old = ConfigStore.toJson(config).apply {
            remove("profiles"); remove("activeProfile")
            getJSONArray("groups").getJSONObject(1).remove("enabled")
        }
        val back = ConfigStore.fromJson(old)
        assertTrue(back.groups.all { it.enabled })
        assertTrue(back.profiles.isEmpty())
    }
}
