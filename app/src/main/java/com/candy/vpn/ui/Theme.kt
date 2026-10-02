// SPDX-License-Identifier: MIT
package com.candy.vpn.ui

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

private val CandyBlue = Color(0xFF3D5AFE)
private val CandyBlueDark = Color(0xFF8C9EFF)
private val CandyTeal = Color(0xFF00897B)
private val CandyTealDark = Color(0xFF4DB6AC)

private val LightColors = lightColorScheme(
    primary = CandyBlue,
    secondary = CandyTeal,
)

private val DarkColors = darkColorScheme(
    primary = CandyBlueDark,
    secondary = CandyTealDark,
)

/** themeMode: 0 = follow system, 1 = light, 2 = dark. */
@Composable
fun CandyTheme(themeMode: Int = 0, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        dark -> DarkColors
        else -> LightColors
    }

    MaterialTheme(colorScheme = colors, content = content)
}
