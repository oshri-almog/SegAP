package com.oshri.multihotspot

import com.oshri.multihotspot.engine.ConfigWriter
import com.oshri.multihotspot.model.HotspotConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static checks on the root engine script (unit tests run with the app module as working directory). */
class EngineScriptTest {

    private val script = File("src/main/assets/mh_engine.sh").readText()

    /**
     * Every `while read -r id ...` loop over groups.conf must either end in a catch-all (`rest`) or name all
     * columns: in sh the last variable takes the rest of the line. A new column once leaked into the DNS option
     * and every client's DHCP failed.
     */
    @Test fun groupsConfReadersAccountForEveryColumn() {
        val columns = ConfigWriter.groupsConf(HotspotConfig.default()).lines().first().split(" ").size
        val loops = Regex("""while read -r (id [a-z_ ]+); do""").findAll(script).map { it.groupValues[1].trim().split(" ") }.toList()
        assertTrue("no groups.conf readers found", loops.isNotEmpty())
        for (vars in loops) {
            val ok = vars.last() == "rest" && vars.size <= columns + 1 || vars.size == columns
            assertTrue("loop reads $vars but groups.conf has $columns columns - end it with 'rest'", ok)
        }
    }

    @Test fun procFilesNeverReadDirectlyByTr() {
        // toybox tr loops forever on a read error from a /proc file whose process just exited.
        assertFalse(Regex("""tr [^|]*< *"?/proc/""").containsMatchIn(script))
    }

    @Test fun statusReportsConfigFingerprintOfFilesInNameOrder() {
        // Engine.writtenConfigSum() hashes the cfg files sorted by name; the engine must hash the same bytes.
        assertTrue(script.contains("""CFGSUM=${'$'}(cat ${'$'}ST/cfg/* 2>/dev/null | md5sum | cut -d' ' -f1)"""))
    }

    @Test fun noCarriageReturns() {
        assertFalse(script.contains('\r'))
    }

    @Test fun dnsOptionOnlyFromValidatedAddresses() {
        assertTrue(script.contains("""dhcp-option=net:${'$'}id,6,${'$'}d_dns"""))
        assertFalse(script.contains("""dhcp-option=net:${'$'}id,6,${'$'}dns"""))
    }

    @Test fun deviceToDeviceTrafficOnlyInItsOwnChain() {
        // Replies for device-to-device flows must not be accepted by the generic ESTABLISHED rule,
        // or removing "access to other networks" wouldn't cut existing connections.
        assertTrue(script.contains("""-A mh_FWD ! -i ${'$'}IF -o ${'$'}IF -m state --state ESTABLISHED,RELATED -j ACCEPT"""))
        assertFalse(script.contains("""-A mh_FWD -o ${'$'}IF -m state --state ESTABLISHED,RELATED -j ACCEPT"""))
        assertEquals(1, Regex("""-A mh_XG -j DROP""").findAll(script).count())
    }
}
