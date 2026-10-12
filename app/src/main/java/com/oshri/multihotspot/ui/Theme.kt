package com.oshri.multihotspot.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun MultiHotspotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Stable per-network accent colours (index in the group list). */
private val groupPalette = listOf(
    Color(0xFF2E7D6B), Color(0xFFB0681E), Color(0xFF5B5FC7), Color(0xFFB23A6B),
    Color(0xFF3A7DB0), Color(0xFF7A8B23), Color(0xFF8E4FB5), Color(0xFF6D6D6D),
)

fun groupColor(index: Int): Color = groupPalette[((index % groupPalette.size) + groupPalette.size) % groupPalette.size]
