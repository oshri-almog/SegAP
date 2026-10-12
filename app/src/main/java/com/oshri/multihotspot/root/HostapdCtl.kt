package com.oshri.multihotspot.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import java.io.File

/**
 * Root-side hostapd control-interface client, run with app_process:
 *   CLASSPATH=<apk> app_process /system/bin com.oshri.multihotspot.root.HostapdCtl <ctrl-socket> <command...>
 *
 * hostapd's control interface is a UNIX datagram socket and replies to the sender's address, so the client
 * must bind its own path (toybox nc can't). Prints the raw reply.
 */
object HostapdCtl {

    class Client(ctrlPath: String) : AutoCloseable {
        private val localPath = File(File(ctrlPath).parentFile, "mh_cli_${Os.getpid()}").path
        private val socket = LocalSocket(LocalSocket.SOCKET_DGRAM)

        init {
            File(localPath).delete()
            socket.bind(LocalSocketAddress(localPath, LocalSocketAddress.Namespace.FILESYSTEM))
            // hostapd runs as user "wifi" and needs write permission on our socket to send the reply.
            Os.chmod(localPath, "777".toInt(8))
            socket.connect(LocalSocketAddress(ctrlPath, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 2000
        }

        private val buf = ByteArray(16 * 1024)

        fun request(cmd: String): String {
            socket.outputStream.write(cmd.toByteArray())
            while (true) {
                val n = socket.inputStream.read(buf)
                if (n <= 0) return ""
                val reply = String(buf, 0, n)
                // Unsolicited events start with "<level>"; we never ATTACH, but skip them to be safe.
                if (!reply.startsWith("<")) return reply
            }
        }

        override fun close() {
            runCatching { socket.close() }
            File(localPath).delete()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 2) {
            System.err.println("usage: HostapdCtl <ctrl-socket> <command...>")
            System.exit(2)
        }
        // Several commands per run (JVM start-up is the slow part): separate them with ";;".
        val commands = args.drop(1).joinToString(" ").split(";;").map { it.trim() }.filter { it.isNotEmpty() }
        Client(args[0]).use { c ->
            for (cmd in commands) {
                println("=== $cmd")
                println(runCatching { c.request(cmd) }.getOrElse { "(error: ${it.message})" })
            }
        }
        System.out.flush()
    }
}
