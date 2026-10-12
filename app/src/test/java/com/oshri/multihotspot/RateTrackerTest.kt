package com.oshri.multihotspot

import com.oshri.multihotspot.model.Client
import com.oshri.multihotspot.model.RateTracker
import com.oshri.multihotspot.ui.formatLinkRate
import com.oshri.multihotspot.ui.formatRate
import org.junit.Assert.assertEquals
import org.junit.Test

class RateTrackerTest {

    private var now = 0L
    private val tracker = RateTracker({ now })

    private fun client(up: Long, down: Long?) =
        Client(mac = "aa:bb:cc:dd:ee:ff", groupId = "g1", ip = null, hostname = null, connectedSinceEpoch = 0, uploadBytes = up, downloadBytes = down)

    private fun step(ms: Long, up: Long, down: Long?): Client { now += ms; return tracker.apply(listOf(client(up, down)))[0] }

    @Test fun firstSampleHasNoRate() {
        val c = step(0, 1000, 5000)
        assertEquals(0L, c.uploadRate); assertEquals(0L, c.downloadRate)
    }

    @Test fun ratesFromDeltas() {
        step(0, 1000, 5000)
        val c = step(2000, 3000, 25000)
        assertEquals(1000L, c.uploadRate)    // 2000 B in 2 s
        assertEquals(10000L, c.downloadRate) // 20000 B in 2 s
    }

    @Test fun tooSoonRepeatsLastRateAndKeepsBaseline() {
        step(0, 0, 0)
        step(2000, 2000, 4000)               // 1000 / 2000 B/s
        val quick = step(300, 2100, 4100)     // second poller, 300 ms later
        assertEquals(1000L, quick.uploadRate); assertEquals(2000L, quick.downloadRate)
        val next = step(1700, 4000, 8000)     // 2000 ms after the baseline, not after the quick sample
        assertEquals(1000L, next.uploadRate); assertEquals(2000L, next.downloadRate)
    }

    @Test fun counterResetGivesZeroNotNegative() {
        step(0, 50_000, 90_000)
        val c = step(2000, 100, 200)          // device reconnected: counters restarted
        assertEquals(0L, c.uploadRate); assertEquals(0L, c.downloadRate)
        val d = step(2000, 2100, 4200)
        assertEquals(1000L, d.uploadRate); assertEquals(2000L, d.downloadRate)
    }

    @Test fun sourceSwitchRebaselines() {
        step(0, 10_000_000, 50_000)           // driver counters
        val c = step(2000, 1_000, null)       // fell back to firewall counter (different scale)
        assertEquals(0L, c.uploadRate); assertEquals(0L, c.downloadRate)
        val d = step(2000, 3_000, null)
        assertEquals(1000L, d.uploadRate)
    }

    @Test fun formatters() {
        assertEquals("800 b/s", formatRate(100))
        assertEquals("9.9 kb/s", formatRate(1237))     // 9896 b/s
        assertEquals("80 kb/s", formatRate(10_000))
        assertEquals("1.6 Mb/s", formatRate(200_000))
        assertEquals("16 Mb/s", formatRate(2_000_000))
        assertEquals("<1", formatLinkRate(999))
        assertEquals("193", formatLinkRate(192_500))
        assertEquals("6", formatLinkRate(6000))
    }
}
