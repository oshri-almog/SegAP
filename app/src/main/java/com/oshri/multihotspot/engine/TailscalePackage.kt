package com.oshri.multihotspot.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The official static Tailscale package SegAP runs for exit nodes (linux/arm64; tailscaled + CLI). Pinned: the
 * download must match [SHA256] exactly, so a newer Tailscale only arrives with a SegAP update that was tested
 * with it. Tailscale is BSD-3-Clause licensed (https://github.com/tailscale/tailscale).
 */
object TailscalePackage {
    const val VERSION = "1.104.1"
    const val URL = "https://pkgs.tailscale.com/stable/tailscale_1.104.1_arm64.tgz"
    const val SHA256 = "f60294374967f3dfd8cf57bbbd474d6cfce32d123e3a8ddeab82a87940daa806"
    const val SIZE = 37_831_403L

    /** Only the arm64 build is pinned. */
    fun supported(): Boolean = android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")

    /**
     * Downloads the package into [dest] and checks its SHA-256. [onProgress] gets (bytes so far, total).
     * Deletes [dest] on any failure.
     */
    suspend fun download(dest: File, onProgress: (Long, Long) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            val conn = URL(URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            try {
                if (conn.responseCode != 200) error("download failed: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: SIZE
                val md = MessageDigest.getInstance("SHA-256")
                var done = 0L
                var lastReport = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (done - lastReport >= 256 * 1024) { lastReport = done; onProgress(done, total) }
                        }
                    }
                }
                onProgress(done, total)
                val sum = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                if (sum != SHA256) error("the download doesn't match Tailscale's published checksum")
                dest
            } finally {
                conn.disconnect()
            }
        }.onFailure { dest.delete() }
    }
}
