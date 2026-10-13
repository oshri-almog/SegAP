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

    @Test fun nsenterAlwaysEndsItsOptions() {
        // toybox nsenter keeps parsing options after the command ("sh -c" became "Unknown option 'c'").
        val calls = Regex("""nsenter -t \S+ -n( --)?""").findAll(script).toList()
        assertTrue("no nsenter calls found", calls.isNotEmpty())
        calls.forEach { assertTrue("nsenter without '--': ${it.value}", it.groupValues[1] == " --") }
    }

    @Test fun upstreamIgnoresTheTailscaleAppsVpn() {
        // Root's own route lookups follow the Tailscale app's VPN; with an exit node it covers 8.8.8.8.
        assertTrue(script.contains("ip route get 8.8.8.8 mark 0x20000"))
        assertTrue(script.contains("grep -v ' dev tun'"))
    }

    @Test fun tsConfReadersEndInCatchAll() {
        val loops = Regex("""while read -r ([xf]_g [a-z_ ]+); do""").findAll(script).map { it.groupValues[1].trim().split(" ") }.toList()
        assertTrue("no ts.conf readers found", loops.size >= 3)
        loops.forEach { assertTrue("ts.conf loop $it must end in a catch-all", it.last().endsWith("rest")) }
        // Columns: group, exit node, share subnet, hostname.
        val columns = ConfigWriter.tailscaleConf(
            HotspotConfig.default().let { c -> c.copy(groups = c.groups.map { it.copy(shareSubnet = true) }) }
        ).lines().first().split(" ").size
        loops.forEach { assertTrue("ts.conf loop $it reads more than $columns columns", it.size <= columns + 1) }
        assertFalse("old exit.conf still referenced", script.contains("exit.conf"))
    }

    @Test fun tailscaleStatusShowsOnlyTheInterfaceName() {
        // $ST/tailscale holds "<iface> <ifindex> <app-exit>"; the app expects just the name.
        assertTrue(script.contains("""echo "TAILSCALE=${'$'}(cut -d' ' -f1 ${'$'}ST/tailscale 2>/dev/null)""""))
    }

    @Test fun exitRuleSitsBetweenTailnetAndUpstream() {
        fun pref(name: String) = Regex("""^$name=(\d+)""", RegexOption.MULTILINE).find(script)!!.groupValues[1].toInt()
        assertTrue(pref("TS_PREF") < pref("EXIT_PREF"))
        assertTrue(pref("EXIT_PREF") < pref("PREF"))
        // Instance uplinks must be looked up before the hotspot's catch-all upstream rule too.
        assertTrue(pref("TS_UP_PREF") < pref("PREF"))
    }

    @Test fun instancesForwardOnlyToPublicAddresses() {
        // An exit node can route private ranges (its LAN, subnet routes): a group must not reach them through it.
        val block = script.substringAfter("for d in ").substringBefore("; do")
        listOf("10.0.0.0/8", "100.64.0.0/10", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "198.18.0.0/15").forEach {
            assertTrue("namespace must drop $it", block.contains(it))
        }
        // ...and no MagicDNS exception: it would let any exit group enumerate the tailnet.
        assertFalse(script.contains("-d 100.100.100.100 -j ACCEPT"))
    }

    @Test fun instancesNeverReachThePhoneItself() {
        assertTrue(script.contains("-A sg_IN -i sg+ -j DROP"))
        assertTrue(script.contains("-I INPUT 1 -j sg_IN"))
    }

    @Test fun namespaceCountsAsReadyOnlyAfterACompleteSetup() {
        val up = script.substringAfter("ts_net_up() {").substringBefore("\n}\n")
        assertTrue(up.indexOf("SETUP-OK") in 0 until up.indexOf("> \$TSD/\$tu_g/netready"))
        assertTrue(script.contains("ts_ready \$tu_g \$tu_n"))
    }

    @Test fun killedWatchdogLeavesNoLocks() {
        val kill = script.substringAfter("kill_engine_procs() {").substringBefore("\n}\n")
        assertTrue(kill.contains("rm -rf \$TSD/*/.lock"))
    }

    @Test fun tailscaleAccessIgnoresOnlyTheAppsDefaultRoute() {
        assertTrue(script.contains("lookup \$a_ts suppress_prefixlength 0"))
    }

    @Test fun deviceToDeviceTrafficOnlyInItsOwnChain() {
        // Replies for device-to-device flows must not be accepted by the generic ESTABLISHED rule,
        // or removing "access to other networks" wouldn't cut existing connections.
        assertTrue(script.contains("""-A mh_FWD ! -i ${'$'}IF -o ${'$'}IF -m state --state ESTABLISHED,RELATED -j ACCEPT"""))
        assertFalse(script.contains("""-A mh_FWD -o ${'$'}IF -m state --state ESTABLISHED,RELATED -j ACCEPT"""))
        assertEquals(1, Regex("""-A mh_XG -j DROP""").findAll(script).count())
    }
}
