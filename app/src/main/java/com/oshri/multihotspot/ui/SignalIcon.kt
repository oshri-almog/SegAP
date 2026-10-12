package com.oshri.multihotspot.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 0 = unknown, 1 (weak) .. 4 (excellent). */
fun signalLevel(rssi: Int?): Int = when {
    rssi == null -> 0
    rssi >= -55 -> 4
    rssi >= -65 -> 3
    rssi >= -75 -> 2
    else -> 1
}

fun signalLabel(level: Int): String = when (level) {
    4 -> "Excellent"
    3 -> "Good"
    2 -> "Fair"
    1 -> "Weak"
    else -> "Unknown"
}

private fun signalColor(level: Int): Color = when (level) {
    4 -> Color(0xFF2FA35A)
    3 -> Color(0xFF7CB342)
    2 -> Color(0xFFF2A007)
    else -> Color(0xFFE5483B)
}

/**
 * Four signal bars. Bars grow/shrink smoothly when the level changes; while the device is moving data
 * ([active]) a highlight sweeps across the lit bars.
 */
@Composable
fun SignalIcon(rssi: Int?, active: Boolean, modifier: Modifier = Modifier.size(28.dp)) {
    val level = signalLevel(rssi)
    val color = signalColor(level)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val fills = (1..4).map { i ->
        animateFloatAsState(
            targetValue = if (i <= level) 1f else 0f,
            animationSpec = tween(durationMillis = 450, delayMillis = (i - 1) * 70),
            label = "bar$i",
        )
    }
    // Only run the infinite animation while traffic flows and some bar is lit (saves battery when idle).
    // The State is read inside the draw block, so each frame only redraws instead of recomposing the row.
    val sweep: State<Float>? = if (active && level > 0) {
        rememberInfiniteTransition(label = "activity").animateFloat(
            initialValue = 0f, targetValue = 5f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "sweep",
        )
    } else null

    Canvas(modifier.semantics { contentDescription = "Signal ${signalLabel(level)}" + (rssi?.let { ", $it dBm" } ?: "") }) {
        val sw = sweep?.value ?: -10f
        val gap = size.width * 0.09f
        val barW = (size.width - gap * 3) / 4
        val radius = CornerRadius(barW / 3, barW / 3)
        for (i in 0 until 4) {
            val h = size.height * (0.32f + 0.68f * (i + 1) / 4f)
            val left = i * (barW + gap)
            drawRoundRect(track, Offset(left, size.height - h), Size(barW, h), radius)
            val f = fills[i].value
            if (f > 0f) {
                val glow = (1f - abs(sw - (i + 1)).coerceIn(0f, 1f))
                val c = if (sweep != null) color.copy(alpha = 0.6f + 0.4f * glow) else color
                val fh = h * f
                drawRoundRect(c, Offset(left, size.height - fh), Size(barW, fh), radius)
            }
        }
    }
}
