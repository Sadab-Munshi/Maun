package org.maun.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Forest = Color(0xFF14342B)
private val Moss = Color(0xFF2F6B55)
private val Mint = Color(0xFF9FD8C0)
private val Paper = Color(0xFFF7F4EE)
private val Ink = Color(0xFF17201C)
private val Night = Color(0xFF0F1513)

private val LightColors = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE9DD),
    onPrimaryContainer = Forest,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E4D9),
    onSurfaceVariant = Color(0xFF4A524D),
    outline = Color(0xFF8A918C),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Forest,
    primaryContainer = Color(0xFF24503F),
    onPrimaryContainer = Color(0xFFCFE9DD),
    background = Night,
    onBackground = Color(0xFFE3E7E3),
    surface = Night,
    onSurface = Color(0xFFE3E7E3),
    surfaceVariant = Color(0xFF1E2925),
    onSurfaceVariant = Color(0xFFB4BDB7),
    outline = Color(0xFF7C857F),
    error = Color(0xFFF2B8B5),
)

/** Colours Material does not name: check results. */
@Immutable
data class StatusColors(val passed: Color, val warning: Color, val failed: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(passed = Color.Unspecified, warning = Color.Unspecified, failed = Color.Unspecified)
}

@Composable
fun MaunTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val status = if (dark) {
        StatusColors(passed = Color(0xFF7FD6A8), warning = Color(0xFFE8C36A), failed = Color(0xFFF2B8B5))
    } else {
        StatusColors(passed = Color(0xFF1F7A4D), warning = Color(0xFF8A5A00), failed = Color(0xFFB3261E))
    }
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
