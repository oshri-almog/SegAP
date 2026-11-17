package com.oshri.multihotspot

import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.engine.ConfigWriter
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.MacMode
import com.oshri.multihotspot.model.Validation
import com.oshri.multihotspot.model.asProfile
import com.oshri.multihotspot.model.shizukuView
import com.oshri.multihotspot.model.syncedProfiles
import com.oshri.multihotspot.model.withSettingsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Transmit power, channel width and MAC choice (root engine). */
class RadioOptionsTest {

    private val config = HotspotConfig.default().copy(bssid = "02:11:22:33:44:55")

    @Test fun engineFiles() {
        assertTrue("MAC=02:11:22:33:44:55" in ConfigWriter.engineConf(config))
        assertTrue("MAC=hw" in ConfigWriter.engineConf(config.copy(macMode = MacMode.HARDWARE)))
        assertFalse("TXPOWER" in ConfigWriter.engineConf(config))
        assertTrue("TXPOWER=11" in ConfigWriter.engineConf(config.copy(txPower = 11)))

        assertFalse("ht_capab" in ConfigWriter.hostapdConf(config, "IL"))
        // The second channel goes where it fits: above for 1-7, below for 8-13.
        assertTrue("ht_capab=[HT40+]" in ConfigWriter.hostapdConf(config.copy(bandwidth40 = true, channel = 1), "IL"))
        assertTrue("ht_capab=[HT40+]" in ConfigWriter.hostapdConf(config.copy(bandwidth40 = true, channel = 7), "IL"))
        assertTrue("ht_capab=[HT40-]" in ConfigWriter.hostapdConf(config.copy(bandwidth40 = true, channel = 13), "IL"))
    }

    @Test fun validation() {
        assertTrue(Validation.config(config.copy(txPower = 11)).isEmpty())
        assertTrue(Validation.config(config.copy(txPower = 30)).any { "power" in it })
    }

    @Test fun status() {
        val s = EngineStatus.parse("STATE=running\nCHANBW=40\nTXPWR=11\nLOG_BEGIN\nLOG_END")
        assertEquals(40, s.bandwidthMhz)
        assertEquals(11, s.txPowerDbm)
        val none = EngineStatus.parse("STATE=running\nLOG_BEGIN\nLOG_END")
        assertEquals(0, none.bandwidthMhz)
        assertNull(none.txPowerDbm)
    }

    @Test fun inProfilesAndJson() {
        val c = config.copy(txPower = 8, bandwidth40 = true, macMode = MacMode.HARDWARE).syncedProfiles()
        val p = c.profiles.single()
        assertEquals(8, p.txPower); assertTrue(p.bandwidth40); assertEquals(MacMode.HARDWARE, p.macMode)
        val other = c.asProfile("p2", "Other").copy(txPower = 0, bandwidth40 = false, macMode = MacMode.RANDOM)
        val switched = c.copy(profiles = c.profiles + other).withSettingsOf(other)
        assertEquals(0, switched.txPower); assertFalse(switched.bandwidth40); assertEquals(MacMode.RANDOM, switched.macMode)

        val withBoth = c.copy(profiles = c.profiles + other)
        assertEquals(withBoth, ConfigStore.fromJson(ConfigStore.toJson(withBoth)))
        // Older configs: no limit, 20 MHz, random MAC.
        val old = ConfigStore.toJson(config).apply { remove("txPower"); remove("bandwidth40"); remove("macMode") }
        val back = ConfigStore.fromJson(old)
        assertEquals(0, back.txPower); assertFalse(back.bandwidth40); assertEquals(MacMode.RANDOM, back.macMode)
    }

    @Test fun rootOnlyOptionsDontNeedApplyWithShizuku() {
        assertEquals(config.shizukuView(), config.copy(txPower = 5, bandwidth40 = true, macMode = MacMode.HARDWARE).shizukuView())
    }
}
