package com.oshri.multihotspot.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * One long-lived `su` process. Reusing it avoids a Magisk grant (and toast) for every command,
 * which matters because status is polled every few seconds.
 */
object RootShell {

    data class Result(val exitCode: Int, val output: String) {
        val ok get() = exitCode == 0
    }

    private val lock = Mutex()
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null

    /** Runs [command] as root, merging stderr into the output. */
    suspend fun run(command: String, timeoutMs: Long = 60_000): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                runLocked(command, timeoutMs)
            } catch (e: Exception) {
                close()
                Result(-1, "root shell error: ${e.message}")
            }
        }
    }

    suspend fun isAvailable(): Boolean = run("id -u", 15_000).let { it.ok && it.output.trim() == "0" }

    private fun ensureStarted() {
        if (process?.isAlive == true) return
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        process = p
        writer = OutputStreamWriter(p.outputStream)
        reader = BufferedReader(InputStreamReader(p.inputStream))
    }

    private fun runLocked(command: String, timeoutMs: Long): Result {
        ensureStarted()
        val marker = "__MH_END_${UUID.randomUUID().toString().replace("-", "")}__"
        val w = writer!!
        // Run in a subshell so `exit` or `cd` in the command can't kill or change the shared shell.
        w.write("( $command ) </dev/null 2>&1; echo \"$marker \$?\"\n")
        w.flush()

        val out = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val r = reader!!
        while (true) {
            if (System.nanoTime() > deadline) {
                close()
                return Result(-2, out.toString() + "\n(timed out)")
            }
            if (!r.ready()) {
                if (process?.isAlive != true) return Result(-1, out.toString() + "\n(root shell exited - root denied?)")
                Thread.sleep(15)
                continue
            }
            val line = r.readLine() ?: return Result(-1, out.toString())
            val idx = line.indexOf(marker)
            if (idx >= 0) {
                if (idx > 0) out.append(line, 0, idx)
                val code = line.substring(idx + marker.length).trim().toIntOrNull() ?: -1
                return Result(code, out.toString().trimEnd())
            }
            out.append(line).append('\n')
        }
    }

    private fun close() {
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        process = null; writer = null; reader = null
    }

    /** Single-quotes [s] for the shell. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
