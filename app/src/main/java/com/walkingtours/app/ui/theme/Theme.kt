package com.walkingtours.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Istanbul-flavoured palette: the blue of the Iznik tiles in the Blue Mosque, the warm sandstone
 * of the Hippodrome, and the teal of the Bosphorus.
 */
private val IznikBlue = Color(0xFF1B5E8C)
private val IznikBlueLight = Color(0xFF7FB6D9)
private val Sandstone = Color(0xFFC8892F)
private val Bosphorus = Color(0xFF0F6E6E)
private val Ink = Color(0xFF101418)

private val LightColors = lightColorScheme(
    primary = IznikBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E7F5),
    onPrimaryContainer = Color(0xFF06283D),
    secondary = Bosphorus,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE9E6),
    onSecondaryContainer = Color(0xFF04302F),
    tertiary = Sandstone,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6E2C4),
    onTertiaryContainer = Color(0xFF43290A),
    background = Color(0xFFF7F9FB),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6EBF0),
    onSurfaceVariant = Color(0xFF44505C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F4F7),
    surfaceContainer = Color(0xFFECEFF2),
    surfaceContainerHigh = Color(0xFFE6EBF0),
    surfaceContainerHighest = Color(0xFFDDE3EA),
)

private val DarkColors = darkColorScheme(
    primary = IznikBlueLight,
    onPrimary = Color(0xFF04263C),
    primaryContainer = Color(0xFF14486E),
    onPrimaryContainer = Color(0xFFD3E7F5),
    secondary = Color(0xFF7FCFC9),
    onSecondary = Color(0xFF052E2D),
    secondaryContainer = Color(0xFF0C504E),
    onSecondaryContainer = Color(0xFFCDE9E6),
    tertiary = Color(0xFFE8BC72),
    onTertiary = Color(0xFF43290A),
    tertiaryContainer = Color(0xFF6A4A16),
    onTertiaryContainer = Color(0xFFF6E2C4),
    background = Ink,
    onBackground = Color(0xFFE2E7EC),
    surface = Color(0xFF171C22),
    onSurface = Color(0xFFE2E7EC),
    surfaceVariant = Color(0xFF2A323B),
    onSurfaceVariant = Color(0xFFC0C9D3),
    surfaceContainerLowest = Color(0xFF0B0F13),
    surfaceContainerLow = Color(0xFF1B2129),
    surfaceContainer = Color(0xFF222A33),
    surfaceContainerHigh = Color(0xFF2A323B),
    surfaceContainerHighest = Color(0xFF353E48),
)

@Composable
fun WalkingToursTheme(
    themeMode: String = "system",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
