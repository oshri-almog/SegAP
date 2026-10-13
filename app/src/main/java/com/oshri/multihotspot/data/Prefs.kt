package com.oshri.multihotspot.data

import android.content.Context
import android.os.SystemClock
import com.oshri.multihotspot.engine.BackendKind
import com.oshri.multihotspot.engine.EngineMode
import kotlin.math.abs

/** Device preferences that aren't part of the hotspot configuration. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("segap", Context.MODE_PRIVATE)

    var engineMode: EngineMode
        get() = runCatching { EngineMode.valueOf(sp.getString("engineMode", null) ?: "") }.getOrDefault(EngineMode.AUTO)
        set(v) { sp.edit().putString("engineMode", v.name).apply() }

    /**
     * The engine that started the hotspot, until that engine reports it stopped; null = none. Forgotten after a
     * reboot (nothing SegAP started survives one).
     */
    var owner: BackendKind?
        get() {
            val kind = runCatching { BackendKind.valueOf(sp.getString("owner", null) ?: "") }.getOrNull() ?: return null
            return kind.takeIf { abs(sp.getLong("ownerBoot", 0) - bootTime()) < 60_000 }
        }
        set(v) {
            sp.edit().putString("owner", v?.name).putLong("ownerBoot", if (v == null) 0 else bootTime()).apply()
        }

    private fun bootTime() = System.currentTimeMillis() - SystemClock.elapsedRealtime()
}
