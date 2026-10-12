package com.oshri.multihotspot.model

/**
 * Turns per-device byte counters into current rates.
 *
 * Two pollers feed it (the visible UI and the foreground service), so samples can arrive close together:
 * within [minIntervalMs] of the previous sample the last rates are repeated and the baseline is kept.
 * Counters restart when a device reconnects, and the engine may switch between the driver's counters and the
 * firewall's (upload only): both cases rebaseline instead of producing a negative or huge rate.
 */
class RateTracker(
    private val nowMs: () -> Long,
    private val minIntervalMs: Long = 1_500,
    private val maxIntervalMs: Long = 30_000,
) {
    private data class Sample(val atMs: Long, val up: Long, val down: Long?, val upRate: Long, val downRate: Long)

    private val samples = HashMap<String, Sample>()

    fun apply(clients: List<Client>): List<Client> {
        val now = nowMs()
        val out = clients.map { c ->
            val prev = samples[c.mac]
            if (prev != null && now - prev.atMs in 0 until minIntervalMs) {
                return@map c.copy(uploadRate = prev.upRate, downloadRate = prev.downRate)
            }
            val dt = if (prev == null) 0L else now - prev.atMs
            val sameSource = prev != null && (prev.down == null) == (c.downloadBytes == null)
            val usable = prev != null && sameSource && dt in minIntervalMs..maxIntervalMs
            val upRate = if (usable && c.uploadBytes >= prev!!.up) (c.uploadBytes - prev.up) * 1000 / dt else 0L
            val down = c.downloadBytes
            val downRate = if (usable && down != null && prev!!.down != null && down >= prev.down) (down - prev.down) * 1000 / dt else 0L
            samples[c.mac] = Sample(now, c.uploadBytes, down, upRate, downRate)
            c.copy(uploadRate = upRate, downloadRate = downRate)
        }
        samples.keys.retainAll(clients.map { it.mac }.toSet())
        return out
    }
}
