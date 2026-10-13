package com.oshri.multihotspot.engine

import com.oshri.multihotspot.model.EngineStatus
import com.oshri.multihotspot.model.HotspotConfig

/** The root engine (mh_engine.sh through su) as a [HotspotBackend]. */
class RootBackend(val engine: Engine) : HotspotBackend {
    override val kind = BackendKind.ROOT

    override suspend fun start(config: HotspotConfig): BackendResult {
        val r = engine.start(config)
        if (r.ok && r.output.contains("OK")) return BackendResult(true)
        val detail = r.output.lines().filter { it.startsWith("ERROR") }.joinToString("\n").ifBlank { r.output.takeLast(400) }
        return BackendResult(false, "$detail\n\n${engine.hostapdLog().takeLast(800)}")
    }

    override suspend fun stop(): BackendResult {
        val r = engine.stop()
        return BackendResult(r.ok, if (r.ok) "" else r.output.takeLast(400))
    }

    override suspend fun status(): EngineStatus = engine.status()

    override suspend fun diagnostics(): String = engine.hostapdLog()
}
