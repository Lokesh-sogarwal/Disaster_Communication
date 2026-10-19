package com.example.disasterconnect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// =========================================================
// MATERIAL COLOUR SCHEMES
// =========================================================
// primary is a neutral so ordinary buttons stay calm; red is reserved for
// emergencies (error / EmergencyRed) and secondary emphasis (SecondaryRed).

private val LightColorScheme = lightColorScheme(
    primary = Charcoal,
    onPrimary = White,
    primaryContainer = SoftCream,
    onPrimaryContainer = Ink,
    secondary = SecondaryRed,
    onSecondary = White,
    secondaryContainer = SoftCream,
    onSecondaryContainer = Ink,
    tertiary = WarningGold,
    onTertiary = Ink,
    tertiaryContainer = SoftCream,
    onTertiaryContainer = Ink,
    error = EmergencyRed,
    onError = White,
    errorContainer = SoftCream,
    onErrorContainer = EmergencyRed,
    background = OffWhite,
    onBackground = Ink,
    surface = White,
    onSurface = Ink,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = MutedInk,
    surfaceTint = Color.Transparent,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight
)

// Reds fail text contrast on dark backgrounds, so in dark mode they are only used
// as fills behind white text. Error text and borders use WarningGold instead.
private val DarkColorScheme = darkColorScheme(
    primary = DayInk,
    onPrimary = NightBackground,
    primaryContainer = NightContainer,
    onPrimaryContainer = DayInk,
    secondary = SecondaryRed,
    onSecondary = White,
    secondaryContainer = NightContainer,
    onSecondaryContainer = SoftCream,
    tertiary = WarningGold,
    onTertiary = Ink,
    tertiaryContainer = SoftCream,
    onTertiaryContainer = Ink,
    error = WarningGold,
    onError = Ink,
    errorContainer = EmergencyRed,
    onErrorContainer = White,
    background = NightBackground,
    onBackground = DayInk,
    surface = NightSurface,
    onSurface = DayInk,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMutedInk,
    surfaceTint = Color.Transparent,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

// =========================================================
// SEMANTIC COLOURS
// =========================================================
// Roles Material has no slot for. Same in light and dark because every pair
// already meets contrast on its own fill.

@Immutable
data class DisasterColors(
    val emergency: Color = EmergencyRed,
    val onEmergency: Color = White,
    val emphasis: Color = SecondaryRed,
    val onEmphasis: Color = White,
    val warning: Color = WarningGold,
    val onWarning: Color = Ink,
    val info: Color = SoftCream,
    val onInfo: Color = Ink
)

private val LocalDisasterColors = staticCompositionLocalOf { DisasterColors() }

/** Access semantic colours with `DisasterTheme.colors.emergency` etc. */
object DisasterTheme {

    val colors: DisasterColors
        @Composable
        @ReadOnlyComposable
        get() = LocalDisasterColors.current
}

// =========================================================
// THEME ENTRY POINT
// =========================================================

@Composable
fun DisasterConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: wallpaper-based colours would replace the emergency palette.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

    CompositionLocalProvider(LocalDisasterColors provides DisasterColors()) {

        MaterialTheme(
          colorScheme = colorScheme,
          typography = Typography,
          shapes = Shapes,
          content = content
        )
    }
}
