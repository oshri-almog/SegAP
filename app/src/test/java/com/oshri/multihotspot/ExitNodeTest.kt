package com.oshri.multihotspot

import com.oshri.multihotspot.data.ConfigStore
import com.oshri.multihotspot.engine.ConfigWriter
import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.Group
import com.oshri.multihotspot.model.HotspotConfig
import com.oshri.multihotspot.model.TailscaleInstance
import com.oshri.multihotspot.model.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Per-network Tailscale exit nodes: model, validation, engine files and status parsing. */
class ExitNodeTest {

    private fun group(id: String, gw: String, name: String = id, exit: String = "", internet: Boolean = true) = Group(
        id = id, name = name, password = "password-$id", gateway = gw, prefix = 24,
        dhcpStart = gw.substringBeforeLast('.') + ".100", dhcpEnd = gw.substringBeforeLast('.') + ".200",
        internet = internet, exitNodeIp = exit, exitNodeName = if (exit.isEmpty()) "" else "node-$id",
    )

    private fun config(vararg groups: Group) = HotspotConfig(ssid = "Test", channel = 6, bssid = "02:00:00:00:00:01", groups = groups.toList())

    @Test fun validation() {
        assertTrue(Validation.group(group("g1", "192.168.10.1", exit = "100.85.0.3")).isEmpty())
        assertTrue(Validation.group(group("g1", "192.168.10.1", exit = "192.168.1.5")).any { "Tailscale address" in it })
        assertTrue(Validation.group(group("g1", "192.168.10.1", exit = "not-an-ip")).any { "Tailscale address" in it })
        assertTrue(Validation.group(group("g1", "192.168.10.1", exit = "100.85.0.3", internet = false)).any { "Internet access" in it })
        // 100.64.0.0/10 ends at 100.127.255.255.
        assertTrue(Validation.group(group("g1", "192.168.10.1", exit = "100.127.255.254")).isEmpty())
        assertTrue(Validation.group(group("g1", "192.168.10.1", exit = "100.128.0.1")).isNotEmpty())
    }

    @Test fun jsonRoundTripKeepsExitNode() {
        val c = config(group("g1", "192.168.10.1", exit = "100.85.0.3"), group("g2", "192.168.20.1"))
        val back = ConfigStore.fromJson(ConfigStore.toJson(c))
        assertEquals(c, back)
        assertEquals("100.85.0.3", back.group("g1")!!.exitNodeIp)
        assertEquals("node-g1", back.group("g1")!!.exitNodeName)
        // Configs saved before the feature have no exit fields.
        val old = ConfigStore.toJson(c).apply { getJSONArray("groups").getJSONObject(0).apply { remove("exitNodeIp"); remove("exitNodeName") } }
        assertFalse(ConfigStore.fromJson(old).group("g1")!!.usesExitNode)
    }

    @Test fun hostnames() {
        assertEquals("segap-home", group("g1", "192.168.10.1", name = "Home").tailscaleHostname)
        assertEquals("segap-kids-tablet", group("g1", "192.168.10.1", name = "  Kids' Tablet!! ").tailscaleHostname)
        // Nothing usable in the name: fall back to the network id.
        assertEquals("segap-g7", group("g7", "192.168.10.1", name = "בית").tailscaleHostname)
        assertTrue(group("g1", "192.168.10.1", name = "x".repeat(200)).tailscaleHostname.length <= 63)
        // A separator right at the 50-character cut must not leave a trailing '-' (invalid DNS label).
        assertEquals("segap-" + "a".repeat(49), group("g1", "192.168.10.1", name = "a".repeat(49) + " b").tailscaleHostname)
    }

    @Test fun exitNodeNeedsPublicDns() {
        val ok = group("g1", "192.168.10.1", exit = "100.85.0.3")
        assertTrue(Validation.group(ok).isEmpty())
        // MagicDNS would be answered by the phone's Tailscale app over the phone's connection; private resolvers
        // aren't forwarded to the exit node at all.
        assertTrue(Validation.group(ok.copy(dns1 = "100.100.100.100")).any { "public DNS" in it })
        assertTrue(Validation.group(ok.copy(dns2 = "192.168.1.1")).any { "public DNS" in it })
        // Without an exit node anything goes.
        assertTrue(Validation.group(group("g1", "192.168.10.1").copy(dns1 = "100.100.100.100")).isEmpty())
    }

    @Test fun publicAddresses() {
        fun pub(s: String) = com.oshri.multihotspot.model.Ipv4.isPublic(com.oshri.multihotspot.model.Ipv4.parse(s)!!)
        assertTrue(pub("1.1.1.1")); assertTrue(pub("8.8.8.8")); assertTrue(pub("100.63.255.255")); assertTrue(pub("100.128.0.1"))
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.100.100.100", "127.0.0.1",
            "169.254.1.1", "198.18.1.1", "198.19.0.1", "224.0.0.1", "255.255.255.255", "0.1.2.3").forEach { assertFalse(it, pub(it)) }
    }

    @Test fun approvalIsNotALogin() {
        val t = TailscaleInstance.parse("TSSTATE=NeedsMachineAuth\n")
        assertTrue(t.needsApproval)
        assertFalse(t.needsLogin)
        assertTrue(TailscaleInstance.parse("TSSTATE=NeedsLogin\n").needsLogin)
    }

    @Test fun tailscaleConfOnlyForGroupsWithAnInstance() {
        val c = config(
            group("g1", "192.168.10.1", name = "Home", exit = "100.85.0.3"),
            group("g2", "192.168.20.1"),
            group("g3", "192.168.30.1", name = "IoT", internet = false).copy(shareSubnet = true),
            group("g4", "192.168.40.1", name = "Lab", exit = "100.82.0.10").copy(shareSubnet = true),
        )
        val expected = "g1 100.85.0.3 0 segap-home\ng3 - 1 segap-iot\ng4 100.82.0.10 1 segap-lab\n"
        assertEquals(expected, ConfigWriter.tailscaleConf(c))
        val dir = File(System.getProperty("java.io.tmpdir"), "segap-ts-test-${System.nanoTime()}")
        try {
            ConfigWriter.writeAll(c, "IL", dir)
            assertEquals(expected, File(dir, "ts.conf").readText())
            // Nothing uses Tailscale instances: no file at all, so the config fingerprint is the same as before the feature.
            ConfigWriter.writeAll(config(group("g2", "192.168.20.1")), "IL", dir)
            assertFalse(File(dir, "ts.conf").exists())
        } finally {
            dir.deleteRecursively()
        }
        // groups.conf keeps its 13 columns: instances live in their own file.
        assertEquals(13, ConfigWriter.groupsConf(c).lines().first().split(" ").size)
    }

    @Test fun exitNodeIgnoredWithoutInternetButSharingStays() {
        // Validation rejects it anyway; the engine file must still never route a no-internet group to an exit node.
        val c = config(group("g1", "192.168.10.1", name = "Home", exit = "100.85.0.3", internet = false).copy(shareSubnet = true))
        assertEquals("g1 - 1 segap-home\n", ConfigWriter.tailscaleConf(c))
        assertEquals("", ConfigWriter.tailscaleConf(config(group("g1", "192.168.10.1", exit = "100.85.0.3", internet = false))))
    }

    @Test fun shareSubnetRoundTrip() {
        val c = config(group("g1", "192.168.10.1").copy(shareSubnet = true))
        assertTrue(ConfigStore.fromJson(ConfigStore.toJson(c)).group("g1")!!.shareSubnet)
        assertTrue(c.group("g1")!!.hasTailscaleInstance)
        assertFalse(config(group("g1", "192.168.10.1")).group("g1")!!.hasTailscaleInstance)
    }

    @Test fun statusParsesExitLines() {
        val s = EngineStatus.parse(
            """
            STATE=running
            TAILSCALE=tun0
            TSBIN=1.104.1
            EXIT g1 active Running 100.85.0.3
            EXIT g3 fallback NeedsLogin 100.82.0.10
            EXIT g4 fallback - 100.82.0.10
            SUBNET g3 192.168.30.0/24 1 Running
            SUBNET g5 192.168.50.0/24 0 -
            LOG_BEGIN
            LOG_END
            """.trimIndent()
        )
        assertEquals("1.104.1", s.tailscaleVersion)
        assertEquals("tun0", s.tailscale)
        assertEquals(3, s.exits.size)
        assertTrue(s.exit("g1")!!.active)
        assertEquals("NeedsLogin", s.exit("g3")!!.backendState)
        assertFalse(s.exit("g3")!!.active)
        assertEquals("", s.exit("g4")!!.backendState)
        assertEquals("100.82.0.10", s.exit("g4")!!.nodeIp)
        assertEquals(2, s.subnets.size)
        assertTrue(s.subnet("g3")!!.approved)
        assertEquals("192.168.30.0/24", s.subnet("g3")!!.subnet)
        assertFalse(s.subnet("g5")!!.approved)
        assertEquals("", s.subnet("g5")!!.backendState)
    }

    @Test fun instanceNeedsLogin() {
        val t = TailscaleInstance.parse(
            """
            TSSTATE=NeedsLogin
            TSAUTH=https://login.tailscale.com/a/abc123
            JSON_BEGIN
            {"BackendState": "NeedsLogin", "AuthURL": "https://login.tailscale.com/a/abc123", "Self": {"HostName": "segap-home", "DNSName": "", "TailscaleIPs": null}}
            JSON_END
            """.trimIndent()
        )
        assertTrue(t.needsLogin)
        assertFalse(t.running)
        assertEquals("https://login.tailscale.com/a/abc123", t.authUrl)
        assertTrue(t.exitNodes.isEmpty())
    }

    @Test fun instanceRunningListsExitNodes() {
        val json = """
            {
              "BackendState": "Running",
              "AuthURL": "",
              "Self": {"HostName": "segap-home", "DNSName": "segap-home.tail1ebc8.ts.net.", "TailscaleIPs": ["100.99.156.121", "fd7a:115c:a1e0::1"],
                       "PrimaryRoutes": ["192.168.10.0/24"]},
              "ExitNodeStatus": {"ID": "nX", "Online": true, "TailscaleIPs": ["100.85.0.3/32", "fd7a:115c:a1e0::3/128"]},
              "Peer": {
                "k1": {"HostName": "Oshri's S24", "DNSName": "oshris-s24-ultra.tail1ebc8.ts.net.", "OS": "android", "Online": true,
                       "ExitNodeOption": true, "TailscaleIPs": ["100.85.0.3", "fd7a:115c:a1e0::3"]},
                "k2": {"HostName": "GL-AXT1800", "DNSName": "gl-axt1800.tail1ebc8.ts.net.", "OS": "linux", "Online": false,
                       "ExitNodeOption": true, "TailscaleIPs": ["100.82.0.10"]},
                "k3": {"HostName": "bitwarden", "DNSName": "bitwarden.tail1ebc8.ts.net.", "OS": "linux", "Online": true,
                       "ExitNodeOption": false, "TailscaleIPs": ["100.112.120.126"]}
              }
            }
        """.trimIndent()
        val t = TailscaleInstance.parse("TSSTATE=Running\nJSON_BEGIN\n$json\nJSON_END\n")
        assertTrue(t.running)
        assertEquals("segap-home", t.selfName)
        assertEquals("100.99.156.121", t.selfIp)
        // Only exit-node options, online first; names are the MagicDNS names Tailscale shows.
        assertEquals(listOf("oshris-s24-ultra", "gl-axt1800"), t.exitNodes.map { it.name })
        assertEquals("100.85.0.3", t.exitNodes[0].ip)
        assertFalse(t.exitNodes[1].online)
        assertEquals("100.85.0.3", t.currentExitIp)
        assertTrue(t.currentExitOnline)
        assertEquals(listOf("192.168.10.0/24"), t.approvedRoutes)
    }

    @Test fun instanceDownOrBroken() {
        assertEquals("down", TailscaleInstance.parse("TSSTATE=down\n").backendState)
        assertEquals("Tailscale for SegAP is not installed", TailscaleInstance.parse("TSERR=Tailscale for SegAP is not installed\nTSSTATE=down\n").error)
        // Garbage JSON keeps what the marker lines said.
        assertEquals("Running", TailscaleInstance.parse("TSSTATE=Running\nJSON_BEGIN\n{not json\nJSON_END\n").backendState)
    }
}
