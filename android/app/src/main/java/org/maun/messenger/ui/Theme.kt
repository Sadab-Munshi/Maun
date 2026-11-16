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

// Every colour Material draws with is named here. Whatever is left out falls
// back to Material's own purple, which shows up in menus and dialogs.
private val LightColors = lightColorScheme(
    primary = Moss,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFE9DD),
    onPrimaryContainer = Forest,
    inversePrimary = Mint,
    secondary = Color(0xFF4E6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E8DF),
    onSecondaryContainer = Color(0xFF0B1F17),
    tertiary = Color(0xFF3C6472),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC9E6F1),
    onTertiaryContainer = Color(0xFF0A2A35),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9E4D9),
    onSurfaceVariant = Color(0xFF4A524D),
    surfaceTint = Moss,
    inverseSurface = Color(0xFF2D322F),
    inverseOnSurface = Color(0xFFEEF1ED),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8A918C),
    outlineVariant = Color(0xFFD5CFC2),
    scrim = Color.Black,
    surfaceBright = Paper,
    surfaceDim = Color(0xFFDDD8CC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2EEE6),
    surfaceContainer = Color(0xFFEDE9DF),
    surfaceContainerHigh = Color(0xFFE9E4D9),
    surfaceContainerHighest = Color(0xFFE3DED2),
)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Forest,
    primaryContainer = Color(0xFF24503F),
    onPrimaryContainer = Color(0xFFCFE9DD),
    inversePrimary = Moss,
    secondary = Color(0xFFB4CCBF),
    onSecondary = Color(0xFF20352B),
    secondaryContainer = Color(0xFF2E4238),
    onSecondaryContainer = Color(0xFFD8E8DF),
    tertiary = Color(0xFFA4CDDD),
    onTertiary = Color(0xFF053542),
    tertiaryContainer = Color(0xFF234C5A),
    onTertiaryContainer = Color(0xFFC9E6F1),
    background = Night,
    onBackground = Color(0xFFE3E7E3),
    surface = Night,
    onSurface = Color(0xFFE3E7E3),
    surfaceVariant = Color(0xFF1E2925),
    onSurfaceVariant = Color(0xFFB4BDB7),
    surfaceTint = Mint,
    inverseSurface = Color(0xFFE3E7E3),
    inverseOnSurface = Color(0xFF2D322F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF7C857F),
    outlineVariant = Color(0xFF34403B),
    scrim = Color.Black,
    surfaceBright = Color(0xFF333D39),
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF151C19),
    surfaceContainer = Color(0xFF19211E),
    surfaceContainerHigh = Color(0xFF1E2925),
    surfaceContainerHighest = Color(0xFF29342F),
)

/** Colours Material does not name: check results, and the ticks of a message that was read. */
@Immutable
data class StatusColors(val passed: Color, val warning: Color, val failed: Color, val read: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(
        passed = Color.Unspecified,
        warning = Color.Unspecified,
        failed = Color.Unspecified,
        read = Color.Unspecified,
    )
}

/** Conversation surfaces for the wallpaper, bubbles, and composer. */
@Immutable
data class ChatColors(
    val wallpaper: Color,
    val patternTint: Color,
    val incomingBubble: Color,
    val incomingContent: Color,
    val incomingMeta: Color,
    val outgoingBubble: Color,
    val outgoingContent: Color,
    val outgoingMeta: Color,
    val composerPill: Color,
    val actionButton: Color,
    val onActionButton: Color,
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(
        wallpaper = Color.Unspecified,
        patternTint = Color.Unspecified,
        incomingBubble = Color.Unspecified,
        incomingContent = Color.Unspecified,
        incomingMeta = Color.Unspecified,
        outgoingBubble = Color.Unspecified,
        outgoingContent = Color.Unspecified,
        outgoingMeta = Color.Unspecified,
        composerPill = Color.Unspecified,
        actionButton = Color.Unspecified,
        onActionButton = Color.Unspecified,
    )
}

/**
 * Backgrounds for the circles that stand in for a picture of a person. All
 * are dark enough for white letters, in the light theme and the dark one.
 */
private val AvatarColors = listOf(
    Color(0xFF2F6B55),
    Color(0xFF3C6472),
    Color(0xFF7A5C2E),
    Color(0xFF8A4B4B),
    Color(0xFF5B5A8C),
    Color(0xFF3F7A7A),
    Color(0xFF8A5A7A),
    Color(0xFF55703A),
)

/** The same person always gets the same colour: it is chosen by their ID. */
fun avatarColor(id: String): Color = AvatarColors[avatarIndex(id, AvatarColors.size)]

@Composable
fun MaunTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val status = if (dark) {
        StatusColors(
            passed = Color(0xFF7FD6A8),
            warning = Color(0xFFE8C36A),
            failed = Color(0xFFF2B8B5),
            read = Color(0xFF6CC3F5),
        )
    } else {
        StatusColors(
            passed = Color(0xFF1F7A4D),
            warning = Color(0xFF8A5A00),
            failed = Color(0xFFB3261E),
            read = Color(0xFF1479C9),
        )
    }
    val chat = if (dark) {
        ChatColors(
            wallpaper = Color(0xFF11171D),
            patternTint = Color(0xFF9FD8C0).copy(alpha = 0.045f),
            incomingBubble = Color(0xFF242E36),
            incomingContent = Color(0xFFE5EAE6),
            incomingMeta = Color(0xFF9BA6A0),
            outgoingBubble = Color(0xFF30485C),
            outgoingContent = Color(0xFFE8F2FA),
            outgoingMeta = Color(0xFFB1C9DA),
            composerPill = Color(0xFF1E2925),
            actionButton = Color(0xFF4E718A),
            onActionButton = Color.White,
        )
    } else {
        ChatColors(
            wallpaper = Color(0xFFF1F2F0),
            patternTint = Color(0xFF2F6B55).copy(alpha = 0.065f),
            incomingBubble = Color.White,
            incomingContent = Ink,
            incomingMeta = Color(0xFF66706B),
            outgoingBubble = Color(0xFFDCEAF2),
            outgoingContent = Color(0xFF1A3444),
            outgoingMeta = Color(0xFF4C697A),
            composerPill = Color.White,
            actionButton = Color(0xFF476C83),
            onActionButton = Color.White,
        )
    }
    CompositionLocalProvider(LocalStatusColors provides status, LocalChatColors provides chat) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
